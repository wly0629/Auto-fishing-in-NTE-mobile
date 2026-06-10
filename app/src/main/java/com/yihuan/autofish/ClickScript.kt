package com.yihuan.autofish

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.min
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.android.Utils

/**
 * 实时屏幕脚本引擎 — 渐进式匹配 + 动态点击跟踪
 *
 * 整个脚本执行一个持续运行的主循环，包含以下步骤：
 *
 * ① 查找并点击 step1
 *    无缓存时在区域查找，有缓存时在缓存位置附近（±NEARBY_OFFSET）查找
 *    找到后计算中心位置，存储并点击，等待 STEP1_POST_CLICK_DELAY_MS
 *
 * ② 连续点击 step1，查找 refer（同样带缓存/附近搜索）
 *    首次查找 left/right 按钮并缓存位置
 *    循环间隔 STEP2_CYCLE_MS，直到 refer 出现
 *
 * ③ 根据 refer 是否存在执行两种子循环：
 *   ③-1 refer 存在：计算 refer 搜索区域（仅一次），查找 cursor，检查左右 target
 *        根据 target 分布长按 left/right 按钮，长按时间 = 距离 / SPEED
 *   ③-2 refer 消失：搜索 step4，找到则点击并重新开始，否则检查 refer 是否重现
 *
 * 所有可调参数集中在 companion object 中，便于修改
 */
class ClickScript(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "ClickScript"
        const val TEMPLATE_DIR = "scripts"

        // ═══════════════════════════════════════════════════════════════
        // 各元素的模板匹配阈值（独立定义，每条带注释说明）
        // ═══════════════════════════════════════════════════════════════
        /** step1 模板匹配阈值 */
        private const val THRESHOLD_STEP1 = 0.80


        /** refer（step3refer）模板匹配阈值 */
        private const val THRESHOLD_REFER = 0.80


        /** cursor 模板匹配阈值 — cursor 图像干净，可设较高值 */
        private const val THRESHOLD_CURSOR = 0.90




        /** target 模板匹配阈值 — target 图像干净，极高置信度 */
        private const val THRESHOLD_TARGET = 0.90



        /** left/right 按钮模板匹配阈值 */
        private const val THRESHOLD_LEFT_RIGHT = 0.80


        /** step4 模板匹配阈值 */
        private const val THRESHOLD_STEP4 = 0.80

        // ═══════════════════════════════════════════════════════════════
        // 各循环等待时间（独立定义，每条带注释说明）
        // ═══════════════════════════════════════════════════════════════
        /** 步骤① 找不到 step1 时重试等待时间（毫秒） */
        private const val LOOP_WAIT_FIND_STEP1_MS = 200L
        /** 步骤① 点击 step1 后等待时间（毫秒） */
        private const val LOOP_WAIT_AFTER_STEP1_CLICK_MS = 4000L
        /** 步骤② 循环查找 refer 的间隔时间（毫秒） */
        private const val LOOP_WAIT_STEP2_CYCLE_MS = 200L

        /** 步骤③ 找不到 cursor 时重试等待时间（毫秒） */
        private const val LOOP_WAIT_FIND_CURSOR_MS = 100L
        /** 步骤③ 跟踪主循环间隔时间（毫秒） */
        private const val LOOP_WAIT_STEP3_CYCLE_MS = 50L
        /** 步骤③-2 找不到 step4 时重试等待时间（毫秒） */
        private const val LOOP_WAIT_FIND_STEP4_MS = 200L

        // ═══════════════════════════════════════════════════════════════
        // 固定周期循环的目标间隔时间
        // 这些值决定每个 capture+match+action 周期的总时长。
        // 改为固定间隔后，各周期的时长一致（匹配快的周期通过 delay 补齐余量）
        // SPEED≈0.36px/ms → 786px/66帧@30fps ≈ 2200ms（约0.36px/ms）
        // 匹配慢的周期则自然占用更长的时间。
        // ═══════════════════════════════════════════════════════════════
        /** 步骤② 循环周期目标间隔（毫秒） */
        private const val CYCLE_INTERVAL_STEP2_MS = 200L
        /** 步骤③ 跟踪主循环周期目标间隔（毫秒） — 目标 20fps，匹配在±2px 狭窄区域内，可稳定在 50ms 以下 */
        private const val CYCLE_INTERVAL_STEP3_MS = 50L
        /** 步骤③-1 refer 匹配的帧间隔 — 每 N 帧才匹配一次，降低开销；50ms/周期 × 5 = 250ms (4fps) */
        private const val REFER_MATCH_INTERVAL = 5
        /** 步骤③-2 step4 搜索循环周期目标间隔（毫秒） */
        private const val CYCLE_INTERVAL_STEP4_MS = 200L

        // ═══════════════════════════════════════════════════════════════
        // 其他可调参数
        // ═══════════════════════════════════════════════════════════════
        /** 所有元素的"附近"搜索偏移量（X/Y ± 此值），用于有缓存位置时的窄范围搜索 */
        private const val NEARBY_OFFSET = 2
        /** 移动速度（像素/毫秒），用于计算长按时间 = 距离 / 速度 */
        private const val SPEED = 0.36
        /** 基础脉冲长度（毫秒），分段脉冲时每个小脉冲的持续时间 */
        private const val BASE_PULSE_MS = 8L

        /** 跟踪超时（毫秒）*/
        private const val TRACKING_TIMEOUT_MS = 60_000L

        /**
         * 最安全的软件深拷贝，使用 copyPixelsToBuffer/copyPixelsFromBuffer
         * 避免 Canvas.drawBitmap 在 VIVO/MTK 上走 GPU 硬件加速路径导致共享内存
         */
        fun deepCopyBitmap(src: Bitmap?): Bitmap? {
            if (src == null || src.isRecycled) return null
            try {
                val w = src.width; val h = src.height
                if (w <= 0 || h <= 0) return null
                val config = src.config ?: Bitmap.Config.ARGB_8888
                val copy = Bitmap.createBitmap(w, h, config)
                val buffer = java.nio.ByteBuffer.allocate(w * h * 4)
                src.copyPixelsToBuffer(buffer)
                buffer.rewind()
                copy.copyPixelsFromBuffer(buffer)
                return copy
            } catch (e: Exception) {
                Log.w(TAG, "深拷贝 Bitmap 失败", e)
                return null
            }
        }
    }

    // ── Callbacks ──
    interface Callback {
        fun onStarted() {}
        fun onStopped() {}
        fun onError(message: String) {}
        fun onPhaseChanged(phase: String) {}
        fun onLog(message: String) {}
        fun onFishCountUpdate(count: Int) {}
    }

    var callback: Callback? = null

    // ── State ──
    @Volatile var isRunning = false; private set
    @Volatile var isPaused = false; private set
    @Volatile var currentPhase = 0; private set

    // 缓存的位置（后续循环复用）
    // 缓存的矩形区域（存储 OpenCV 匹配到的完整矩形，后续搜索在此区域 ± 偏移量内进行）
    private var cachedStep1Rect: Rect? = null
    private var cachedReferRect: Rect? = null
    private var cachedLeftPos: Point? = null
    private var cachedRightPos: Point? = null
    private var cachedStep4Rect: Rect? = null

    // refer 搜索区域（步骤③ 第一次找到 refer 时计算确定，后续永久复用，不重置）
    private var referSearchArea: Rect? = null
    // refer 匹配帧计数器 — 每 pass 一次 ③-1 周期 +1，达到 REFER_MATCH_INTERVAL 时执行 refer 匹配
    private var referMatchCycle = 0

    // 左右 target 最后已知位置
    private var lastLeftTargetPos: Point? = null
    private var lastRightTargetPos: Point? = null

    // 步骤状态标识
    private var isFirstLoop = true
    /** 跳过步骤①和②，直接进入步骤③跟踪（用于检测阶段找到refer时） */
    private var enterTrackingDirectly = false

    // Template cache
    private val templateCache = mutableMapOf<String, Bitmap>()

    // Frame & Job
    @Volatile private var latestFrame: Bitmap? = null
    /** 帧缓冲锁：解决 VIVO/MTK 上 Bitmap native 数据竞态问题 */
    private val frameLock = Any()
    private var scriptJob: Job? = null

    /** 最大轮数（0 = 无限） */
    var maxLoops: Int = 0
    /** 外部起始计数 */
    var startingCount: Int = 0
    private var loopCount = 0

    /** 外部帧回调：ScreenCaptureService 的 imageToBitmap 已产出独立 Bitmap，无需重复深拷贝 */
    fun onFrameReceived(frame: Bitmap) {
        // imageToBitmap 已做了逐像素独立深拷贝，frame 是独立于 ImageReader 缓冲区的 Bitmap
        // 不再调用 deepCopyBitmap，避免主线程额外 10MB 分配 + 像素遍历导致的 GC 卡顿
        synchronized(frameLock) {
            // 旧帧由 captureFrame 取走后自行回收，这里不主动 recycle（其他线程可能还在用）
            latestFrame = frame
        }
    }

    /**
     * 线程安全地捕获当前帧的深拷贝（供非热路径使用，匹配后需 recycle）。
     *
     * 注意：deepCopyBitmap 调用在 synchronized 块外部执行，
     * 避免在主线程 onFrameReceived 试图写入 latestFrame 时被锁阻塞。
     * synchronized 仅用于读取 latestFrame 的引用，不包含耗时的像素拷贝。
     */
    private fun captureFrame(): Bitmap? {
        val frame = synchronized(frameLock) { latestFrame }
        return frame?.let { if (it.isRecycled) null else deepCopyBitmap(it) }
    }

    /**
     * 轻量级帧窥探（供 Step ③-1 热循环使用，不做深拷贝）。
     *
     * onFrameReceived 传入的 frame 已是 imageToBitmap 的独立深拷贝，
     * 此处直接返回引用，不重复 deepCopyBitmap，避免每 50ms 额外 10MB 分配。
     * 返回的 frame 是共享引用，调用方不得 recycle。
     */
    private fun peekFrame(): Bitmap? {
        val frame = synchronized(frameLock) { latestFrame }
        if (frame == null || frame.isRecycled) return null
        return frame
    }

    /**
     * 固定周期延迟辅助方法。
     * 记录周期开始时间，计算已消耗时间，delay 剩余时间使得整个周期 = targetIntervalMs。
     * 如果已消耗时间 >= targetIntervalMs，则立即返回（不 delay）。
     * 这样保证每个 capture+match+action 周期具有一致的总时长。
     */
    private suspend fun delayUntilCycleEnd(cycleStartMs: Long, targetIntervalMs: Long) {
        val elapsed = System.currentTimeMillis() - cycleStartMs
        val remaining = targetIntervalMs - elapsed
        if (remaining > 0) {
            delay(remaining)
        }
    }

    // ══════════════════════════════════════════════
    // 带预捕获帧的重载匹配方法（用于 Step ③-1 单帧多匹配优化）
    // ══════════════════════════════════════════════

    /**
     * 带预捕获帧的 [findElementWithCache] 版本，避免同周期内多次深拷贝帧。
     * [preCapturedFrame] 由调用方提供并在整个周期内有效，调用方负责回收。
     */
    private fun findElementWithCache(
        templateName: String,
        threshold: Double,
        cachedRect: Rect?,
        defaultSearchArea: Rect?,
        preCapturedFrame: Bitmap
    ): org.opencv.core.Rect? {
        val template = loadTemplate(templateName) ?: return null

        val searchArea: Rect? = if (cachedRect != null) {
            val screenW = getScreenWidth()
            val screenH = getScreenHeight()
            val l = (cachedRect.left - NEARBY_OFFSET).coerceIn(0, screenW)
            val t = (cachedRect.top - NEARBY_OFFSET).coerceIn(0, screenH)
            val r = (cachedRect.right + NEARBY_OFFSET).coerceIn(0, screenW)
            val b = (cachedRect.bottom + NEARBY_OFFSET).coerceIn(0, screenH)
            if (r <= l || b <= t) null
            else Rect(l, t, r, b)
        } else {
            defaultSearchArea
        }

        return try {
            ImageMatcher.matchTemplate(preCapturedFrame, template, threshold, searchArea, null)?.rect
        } catch (e: Exception) {
            null
        }
        // preCapturedFrame 由调用方统一回收
    }

    /**
     * 带预捕获帧的 [findCursorInArea] 版本。
     */
    private fun findCursorInArea(area: Rect, preCapturedFrame: Bitmap): org.opencv.core.Rect? {
        val searchRect = area
        val searchW = searchRect.right - searchRect.left
        val searchH = searchRect.bottom - searchRect.top
        if (searchW <= 0 || searchH <= 0) return null

        val template = loadTemplate("step3cursor.jpg") ?: return null

        return try {
            ImageMatcher.matchTemplate(preCapturedFrame, template, THRESHOLD_CURSOR, searchRect, null)?.rect
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 带预捕获帧的 [findTargetInSide] 版本。
     */
    private fun findTargetInSide(
        isLeft: Boolean,
        cursorRect: org.opencv.core.Rect,
        searchArea: Rect,
        preCapturedFrame: Bitmap
    ): org.opencv.core.Rect? {
        val targetL: Int = searchArea.left
        val targetR: Int = searchArea.right

        val actualL: Int
        val actualR: Int
        if (isLeft) {
            actualL = targetL
            actualR = cursorRect.x
        } else {
            actualL = cursorRect.x + cursorRect.width
            actualR = targetR
        }

        val actualW = actualR - actualL
        val actualH = searchArea.bottom - searchArea.top
        if (actualW <= 0 || actualH <= 0) return null

        val searchRect = Rect(actualL, searchArea.top, actualR, searchArea.bottom)

        val template = loadTemplate("step3target.jpg") ?: return null

        return try {
            ImageMatcher.matchTemplate(preCapturedFrame, template, THRESHOLD_TARGET, searchRect, null)?.rect
        } catch (e: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════
    // 灰度 Mat 优化匹配（Step ③-1 热循环：单帧转灰一次，复用多次）
    // ══════════════════════════════════════════════

    /**
     * 将 Bitmap 转换为灰度 Mat（用于单帧多次匹配优化，避免重复 bitmapToMat + cvtColor）
     */
    private fun bitmapToGrayMat(bitmap: Bitmap): Mat? {
        return try {
            val screenMat = Mat()
            Utils.bitmapToMat(bitmap, screenMat)
            val grayMat = Mat()
            Imgproc.cvtColor(screenMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            screenMat.release()
            grayMat
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 使用灰度 Mat 查找 cursor（复用单帧灰度 Mat，避免 bitmapToMat + cvtColor）
     */
    private fun findCursorInAreaGray(area: Rect, grayMat: Mat): org.opencv.core.Rect? {
        if (area.left >= area.right || area.top >= area.bottom) return null
        val template = loadTemplate("step3cursor.jpg") ?: return null
        return try {
            ImageMatcher.matchTemplate(grayMat, template, THRESHOLD_CURSOR, area, null)?.rect
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 使用灰度 Mat 查找 target（复用单帧灰度 Mat，避免 bitmapToMat + cvtColor）
     */
    private fun findTargetInSideGray(
        isLeft: Boolean,
        cursorRect: org.opencv.core.Rect,
        searchArea: Rect,
        grayMat: Mat
    ): org.opencv.core.Rect? {
        val targetL = searchArea.left
        val targetR = searchArea.right

        val actualL: Int
        val actualR: Int
        if (isLeft) {
            actualL = targetL
            actualR = cursorRect.x
        } else {
            actualL = cursorRect.x + cursorRect.width
            actualR = targetR
        }

        if (actualR - actualL <= 0 || searchArea.bottom - searchArea.top <= 0) return null
        val searchRect = Rect(actualL, searchArea.top, actualR, searchArea.bottom)

        val template = loadTemplate("step3target.jpg") ?: return null

        return try {
            ImageMatcher.matchTemplate(grayMat, template, THRESHOLD_TARGET, searchRect, null)?.rect
        } catch (e: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════

    fun start() {
        if (isRunning) { log("⚠️ script already running"); return }
        if (!OpenCVHelper.isInitialized()) {
            val ok = OpenCVHelper.init()
            if (!ok) {
                Log.e(TAG, "❌ OpenCV 原生库加载失败，无法启动脚本")
                callback?.onError("OpenCV 原生库加载失败")
                return
            }
        }
        isRunning = true
        resetState()
        callback?.onStarted()

        scriptJob = scope.launch(Dispatchers.Default) {
            try {
                runMainScript()
            } catch (e: CancellationException) {
                log("⏹ script cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "script exception", e)
                log("❌ script error: ${e.message}")
            } finally {
                isRunning = false
                callback?.onStopped()
            }
        }
    }

    fun stop() {
        isRunning = false
        scriptJob?.cancel()
        scriptJob = null
        log("⏹ script stopped")
    }

    fun setPhase(desc: String) {
        callback?.onPhaseChanged(desc)
        log(desc)
    }

    // ══════════════════════════════════════════════
    // 主脚本逻辑
    // ══════════════════════════════════════════════

    private suspend fun runMainScript() {
        setPhase("🚀 主脚本启动")

        // 等待首帧
        if (awaitFirstFrame() == null) return

        // 运行时方向检测：非横屏则等待（最多10秒），超时退出
        if (!waitForLandscapeOrStop()) return

        // ═══════════════════════════════════════════
        // 检测当前游戏状态 — 先查找 step4/refer/step1 确认在哪一步
        // ═══════════════════════════════════════════
        setPhase("🔍 检测当前游戏状态")

        val detectedStep4 = findElementWithCache(
            templateName = "step4.jpg",
            threshold = THRESHOLD_STEP4,
            cachedRect = null,
            defaultSearchArea = getStep4DefaultArea()
        )
        val detectedRefer = findElementWithCache(
            templateName = "step3refer.jpg",
            threshold = THRESHOLD_REFER,
            cachedRect = null,
            defaultSearchArea = getReferDefaultArea()
        )
        val detectedStep1 = findElementWithCache(
            templateName = "step1.jpg",
            threshold = THRESHOLD_STEP1,
            cachedRect = null,
            defaultSearchArea = getStep1DefaultArea()
        )

        when {
            detectedStep4 != null -> {
                // step4 存在 → 点击 step4 直到 step1 出现（恢复操作，不计数）
                log("🔍 检测到 step4 → 直接恢复步骤④")
                cachedStep4Rect = Rect(
                    detectedStep4.x, detectedStep4.y,
                    detectedStep4.x + detectedStep4.width,
                    detectedStep4.y + detectedStep4.height
                )
                val step4Center = Point(
                    detectedStep4.x + detectedStep4.width / 2,
                    detectedStep4.y + detectedStep4.height / 2
                )
                doClick(step4Center.x, step4Center.y)
                // 循环点击 step4 直到 step1 出现（恢复操作，不计数）
                var step4PhaseCount = 0
                while (isRunning) {
                    val newStep1 = findElementWithCache(
                        templateName = "step1.jpg",
                        threshold = THRESHOLD_STEP1,
                        cachedRect = null,
                        defaultSearchArea = getStep1DefaultArea()
                    )
                    if (newStep1 != null) {
                        cachedStep1Rect = Rect(
                            newStep1.x, newStep1.y,
                            newStep1.x + newStep1.width,
                            newStep1.y + newStep1.height
                        )
                        log("🔍 step4 → step1 恢复成功，进入主循环（不计数）")
                        break
                    }
                    doClick(step4Center.x, step4Center.y)
                    step4PhaseCount++
                    delay(50)
                }
            }
            detectedRefer != null -> {
                // refer 存在 → 缓存后直接进入步骤③跟踪
                log("🔍 检测到 refer → 缓存后直接进入步骤③跟踪")
                cachedReferRect = Rect(
                    detectedRefer.x, detectedRefer.y,
                    detectedRefer.x + detectedRefer.width,
                    detectedRefer.y + detectedRefer.height
                )
                if (detectedStep1 != null) {
                    cachedStep1Rect = Rect(
                        detectedStep1.x, detectedStep1.y,
                        detectedStep1.x + detectedStep1.width,
                        detectedStep1.y + detectedStep1.height
                    )
                }
                enterTrackingDirectly = true
                cacheLeftRightButtons()
            }
            detectedStep1 != null -> {
                log("🔍 检测到 step1 → 缓存后进入主循环")
                cachedStep1Rect = Rect(
                    detectedStep1.x, detectedStep1.y,
                    detectedStep1.x + detectedStep1.width,
                    detectedStep1.y + detectedStep1.height
                )
            }
            else -> {
                log("🔍 未检测到任何已知状态，从主循环从头开始")
            }
        }

        mainLoop@ while (isRunning) {
            // 每轮开始检查屏幕方向，竖屏时等待最多10秒
            if (!waitForLandscapeOrStop()) break

            if (enterTrackingDirectly) {
                enterTrackingDirectly = false
                log("━━━ 开始第 ${loopCount + 1} 轮（从步骤③跟踪开始，跳过①和②）━━━")
            } else {
                log("━━━ 开始第 ${loopCount + 1} 轮 ━━━")

                // ═══════════════════════════════════════════
                // ① 查找并点击 step1
                // ═══════════════════════════════════════════
                if (!isRunning) break
                setPhase("① 查找并点击 step1")

                val step1Rect = findElementWithCache(
                    templateName = "step1.jpg",
                    threshold = THRESHOLD_STEP1,
                    cachedRect = cachedStep1Rect,
                    defaultSearchArea = getStep1DefaultArea()
                )

            if (step1Rect == null || !isRunning) {
                if (!isRunning) break
                log("⚠️ step1 未找到，${LOOP_WAIT_FIND_STEP1_MS}ms 后重试")
                delay(LOOP_WAIT_FIND_STEP1_MS)
                continue
            }

            val step1Center = Point(
                step1Rect.x + step1Rect.width / 2,
                step1Rect.y + step1Rect.height / 2
            )
            if (cachedStep1Rect == null) {
                cachedStep1Rect = Rect(step1Rect.x, step1Rect.y, step1Rect.x + step1Rect.width, step1Rect.y + step1Rect.height)
                log("📌 缓存 step1 区域: (${cachedStep1Rect!!.left},${cachedStep1Rect!!.top})-(${cachedStep1Rect!!.right},${cachedStep1Rect!!.bottom}) 尺寸=${step1Rect.width}x${step1Rect.height}")
            }
            log("✅ step1 定位: (${step1Center.x}, ${step1Center.y})")

            doClick(step1Center.x, step1Center.y)
            log("👆 点击 step1")

            // 等待4秒，前1秒内每200ms检查refer是否已出现
            val step1ClickTime = System.currentTimeMillis()
            var referFoundEarly = false
            while (System.currentTimeMillis() - step1ClickTime < 1000 && isRunning) {
                val earlyRefer = findElementWithCache(
                    templateName = "step3refer.jpg",
                    threshold = THRESHOLD_REFER,
                    cachedRect = null,
                    defaultSearchArea = getReferDefaultArea()
                )
                if (earlyRefer != null) {
                    log("✅ 前1秒内检测到 refer → 跳过步骤②，直接进入跟踪")
                    cachedReferRect = Rect(earlyRefer.x, earlyRefer.y, earlyRefer.x + earlyRefer.width, earlyRefer.y + earlyRefer.height)
                    referFoundEarly = true
                    break
                }
                delay(200)
            }
            // 如果前1秒没有refer，等待剩余时间
            if (!referFoundEarly) {
                val remainingWait = LOOP_WAIT_AFTER_STEP1_CLICK_MS - (System.currentTimeMillis() - step1ClickTime)
                if (remainingWait > 0) delay(remainingWait)
            }

            // ═══════════════════════════════════════════
            // ② 连续点击 step1，查找 refer，同时首次记录 left/right
            // ═══════════════════════════════════════════
            if (!isRunning) break
            setPhase("② 循环点击 step1 等待 refer")

            var referRect: org.opencv.core.Rect?
            var step1ClickCount = 0

            // 如果前1秒内已找到refer，跳过步骤②的循环
            if (referFoundEarly && cachedReferRect != null) {
                log("⏩ 前1秒已找到 refer，跳过步骤②循环")
                cachedLeftPos?.let { cachedRightPos?.let { log("left/right 按钮已缓存") } }
            } else {
            do {
                val cycleStartMs = System.currentTimeMillis()
                step1ClickCount++
                if (!isRunning) break@mainLoop

                // 点击 step1 位置（使用缓存的矩形中心）
                cachedStep1Rect?.let { doClick((it.left + it.right) / 2, (it.top + it.bottom) / 2) }

                // 查找 refer
                referRect = findElementWithCache(
                    templateName = "step3refer.jpg",
                    threshold = THRESHOLD_REFER,
                    cachedRect = cachedReferRect,
                    defaultSearchArea = getReferDefaultArea()
                )

                // 如果没有记录过 left/right，查找并记录
                if (cachedLeftPos == null || cachedRightPos == null) {
                    cacheLeftRightButtons()
                }

                // 固定周期延迟：使每个 click+match 周期时长一致
                delayUntilCycleEnd(cycleStartMs, CYCLE_INTERVAL_STEP2_MS)
            } while (referRect == null && isRunning && !referFoundEarly)
            }
            // 如果 referFoundEarly 则 referRect 已在上面设置，这里直接从缓存取
            if (referFoundEarly) {
                // referRect 已在上面通过 earlyRefer 设置 cachedReferRect
            }

            if (!isRunning) break
            if (!referFoundEarly) {
                log("✅ refer 在 ${step1ClickCount} 次 step1 点击后出现")
                // 缓存 refer 矩形区域
                cachedReferRect = Rect(referRect!!.x, referRect!!.y, referRect!!.x + referRect!!.width, referRect!!.y + referRect!!.height)
                log("📌 缓存 refer 区域: (${cachedReferRect!!.left},${cachedReferRect!!.top})-(${cachedReferRect!!.right},${cachedReferRect!!.bottom}) 尺寸=${referRect!!.width}x${referRect!!.height}")
            }
            }  // 结束 enterTrackingDirectly 的 else 块

            // ═══════════════════════════════════════════
            // ③ 根据 refer 存在与否执行两种子循环
            // ═══════════════════════════════════════════
            if (!isRunning) break
            setPhase("③ 动态 target 跟踪")

            val trackingStartTime = System.currentTimeMillis()
            var inReferTracking = true   // true = ③-1, false = ③-2
            referMatchCycle = 0           // refer 帧计数器重置
            // referSearchArea 首次找到 refer 时计算一次，后续永久复用
            while (isRunning) {
                val cycleStartMs = System.currentTimeMillis()

                // 检查超时
                if (System.currentTimeMillis() - trackingStartTime > TRACKING_TIMEOUT_MS) {
                    log("⏱ 跟踪超时 ${TRACKING_TIMEOUT_MS / 1000}s，强制退出")
                    // 重置缓存重新开始
                    resetPositionCache()
                    isFirstLoop = true
                    continue@mainLoop
                }

                if (inReferTracking) {
                    // ══════════════════════════════════
                    // ③-1 refer 存在 → 跟踪 cursor 和 target
                    // ══════════════════════════════════

                    // ★ 核心优化：每周期只捕获一帧，所有匹配复用同一帧
                    //   ★ 增强优化：帧转为灰度 Mat 一次，cursor+target 匹配直接复用灰度 Mat，
                    //     避免 bitmapToMat + cvtColor 重复 3 次；使用 peekFrame 无需深拷贝（帧已独立）
                    val cycleFrame = peekFrame()

                    if (cycleFrame != null) {
                        // 帧转换为灰度 Mat 一次，后续 cursor+target 匹配复用
                        val cycleGray = bitmapToGrayMat(cycleFrame)
                        if (cycleGray == null) {
                            // 转换失败，跳过本周期
                            delayUntilCycleEnd(cycleStartMs, CYCLE_INTERVAL_STEP3_MS)
                            continue
                        }
                        // cycleFrame 是共享引用，自然被 onFrameReceived 替换，不 recycle

                        try {
                            // ── refer 匹配（每 N 帧执行一次，复用 cycleFrame Bitmap） ──
                            var currentReferRect: org.opencv.core.Rect? = null
                            if (referMatchCycle % REFER_MATCH_INTERVAL == 0) {
                                currentReferRect = findElementWithCache(
                                    templateName = "step3refer.jpg",
                                    threshold = THRESHOLD_REFER,
                                    cachedRect = cachedReferRect,
                                    defaultSearchArea = getReferDefaultArea(),
                                    preCapturedFrame = cycleFrame
                                )
                                if (currentReferRect == null) {
                                    cachedReferRect = null
                                }
                            }

                            if (currentReferRect == null) {
                                if (cachedReferRect == null) {
                                    currentReferRect = findElementWithCache(
                                        templateName = "step3refer.jpg",
                                        threshold = THRESHOLD_REFER,
                                        cachedRect = null,
                                        defaultSearchArea = getReferDefaultArea(),
                                        preCapturedFrame = cycleFrame
                                    )
                                }

                                if (currentReferRect == null && cachedReferRect == null) {
                                    log("refer 消失 → 切换到 step4 搜索")
                                    inReferTracking = false
                                    referMatchCycle = 0
                                    continue
                                }
                            }

                            referMatchCycle++

                            if (referSearchArea == null && currentReferRect != null) {
                                referSearchArea = calculateReferSearchArea(
                                    currentReferRect.x + currentReferRect.width / 2,
                                    currentReferRect.y,
                                    currentReferRect.y + currentReferRect.height
                                )
                                log("📐 计算 refer 搜索区域完成")
                            }

                            val area = referSearchArea
                            if (area == null || area.left >= area.right || area.top >= area.bottom) {
                                if (area == null) log("⚠️ refer 搜索区域未初始化，等待...")
                                else log("⚠️ refer 搜索区域无效，等待...")
                                continue
                            }

                            // 查找 cursor（复用灰度 Mat，避免 bitmapToMat + cvtColor）
                            val cursorRect = findCursorInAreaGray(area, cycleGray)
                            if (cursorRect == null) {
                                // cursor 未找到 → 短按 left 或 right 后重新查找
                                log("⚠️ cursor 未找到 → 短按按钮后重新查找")
                                if (cachedRightPos != null) {
                                    doClick(cachedRightPos!!.x, cachedRightPos!!.y)
                                } else if (cachedLeftPos != null) {
                                    doClick(cachedLeftPos!!.x, cachedLeftPos!!.y)
                                }
                                continue
                            }

                            val cursorCenterX = cursorRect.x + cursorRect.width / 2

                            // 检查两侧 target（复用灰度 Mat）
                            val leftTarget = findTargetInSideGray(
                                isLeft = true,
                                cursorRect = cursorRect,
                                searchArea = area,
                                grayMat = cycleGray
                            )
                            val rightTarget = findTargetInSideGray(
                                isLeft = false,
                                cursorRect = cursorRect,
                                searchArea = area,
                                grayMat = cycleGray
                            )

                            when {
                                leftTarget != null && rightTarget != null -> {
                                    lastLeftTargetPos = Point(
                                        leftTarget.x + leftTarget.width / 2,
                                        leftTarget.y + leftTarget.height / 2
                                    )
                                    lastRightTargetPos = Point(
                                        rightTarget.x + rightTarget.width / 2,
                                        rightTarget.y + rightTarget.height / 2
                                    )
                                    log("⏸ 左右都有 target，等待...")
                                }
                                rightTarget != null -> {
                                    val rightTargetCenterX = rightTarget.x + rightTarget.width / 2
                                    val leftTargetX = lastLeftTargetPos?.x
                                        ?: (area.left + cursorCenterX) / 2
                                    val holdMs = abs(cursorCenterX - leftTargetX) / SPEED
                                    val maxPulseMs = holdMs.toLong().coerceIn(5L, 40L)
                                    log("🔴 仅右侧有 target → 长按 RIGHT ${maxPulseMs}ms (分段监测) " +
                                        "(cursor=$cursorCenterX, leftTarget=$leftTargetX)")

                                    // ★ 监测式分段长按：拆分为短脉冲，每段间捕获帧检查方向是否变化
                                    if (cachedRightPos != null) {
                                        val pulseStartTime = System.currentTimeMillis()
                                        var accumulatedMs = 0L
                                        while (accumulatedMs < maxPulseMs && isRunning) {
                                            val remaining = maxPulseMs - accumulatedMs
                                            val burstMs = minOf(BASE_PULSE_MS, remaining)
                                            doLongClick(cachedRightPos!!.x, cachedRightPos!!.y, burstMs)
                                            delay(5)

                                            // 捕获最新帧，重新检查 cursor 和 target 情况
                                            val checkFrame = peekFrame()
                                            if (checkFrame != null) {
                                                val checkGray = bitmapToGrayMat(checkFrame)
                                                if (checkGray != null) {
                                                    try {
                                                        val areaThis = referSearchArea
                                                        if (areaThis != null && areaThis.left < areaThis.right) {
                                                            val cursorNow = findCursorInAreaGray(areaThis, checkGray)
                                                            if (cursorNow != null) {
                                                                val cursorNowX = cursorNow.x + cursorNow.width / 2
                                                                // 检查是否有左侧 target 出现（方向反转信号）
                                                                val leftNow = findTargetInSideGray(
                                                                    isLeft = true,
                                                                    cursorRect = cursorNow,
                                                                    searchArea = areaThis,
                                                                    grayMat = checkGray
                                                                )
                                                                // cursor 接近目标或左侧出现 target → 提前停止
                                                                if (leftNow != null || abs(cursorNowX - leftTargetX) <= 4) {
                                                                    log("⏹ 右按监测: ${if (leftNow != null) "左侧出现 target" else "cursor 已到达"}，提前停止")
                                                                    break
                                                                }
                                                            }
                                                        }
                                                    } finally {
                                                        checkGray.release()
                                                    }
                                                }
                                            }

                                            accumulatedMs = System.currentTimeMillis() - pulseStartTime
                                        }
                                        lastRightTargetPos = Point(
                                            rightTarget.x + rightTarget.width / 2,
                                            rightTarget.y + rightTarget.height / 2
                                        )
                                    } else {
                                        log("⚠️ right 按钮未缓存")
                                    }
                                }
                                leftTarget != null -> {
                                    val leftTargetCenterX = leftTarget.x + leftTarget.width / 2
                                    val rightTargetX = lastRightTargetPos?.x
                                        ?: (cursorCenterX + area.right) / 2
                                    val holdMs = abs(rightTargetX - cursorCenterX) / SPEED
                                    val maxPulseMs = holdMs.toLong().coerceIn(8L, 40L)
                                    log("🔴 仅左侧有 target → 长按 LEFT ${maxPulseMs}ms (分段监测) " +
                                        "(cursor=$cursorCenterX, rightTarget=$rightTargetX)")

                                    // ★ 监测式分段长按：拆分为短脉冲，每段间捕获帧检查方向是否变化
                                    if (cachedLeftPos != null) {
                                        val pulseStartTime = System.currentTimeMillis()
                                        var accumulatedMs = 0L
                                        while (accumulatedMs < maxPulseMs && isRunning) {
                                            val remaining = maxPulseMs - accumulatedMs
                                            val burstMs = minOf(BASE_PULSE_MS, remaining)
                                            doLongClick(cachedLeftPos!!.x, cachedLeftPos!!.y, burstMs)
                                            delay(5)

                                            // 捕获最新帧，重新检查 cursor 和 target 情况
                                            val checkFrame = peekFrame()
                                            if (checkFrame != null) {
                                                val checkGray = bitmapToGrayMat(checkFrame)
                                                if (checkGray != null) {
                                                    try {
                                                        val areaThis = referSearchArea
                                                        if (areaThis != null && areaThis.left < areaThis.right) {
                                                            val cursorNow = findCursorInAreaGray(areaThis, checkGray)
                                                            if (cursorNow != null) {
                                                                val cursorNowX = cursorNow.x + cursorNow.width / 2
                                                                // 检查是否有右侧 target 出现（方向反转信号）
                                                                val rightNow = findTargetInSideGray(
                                                                    isLeft = false,
                                                                    cursorRect = cursorNow,
                                                                    searchArea = areaThis,
                                                                    grayMat = checkGray
                                                                )
                                                                // cursor 接近目标或右侧出现 target → 提前停止
                                                                if (rightNow != null || abs(cursorNowX - rightTargetX) <= 4) {
                                                                    log("⏹ 左按监测: ${if (rightNow != null) "右侧出现 target" else "cursor 已到达"}，提前停止")
                                                                    break
                                                                }
                                                            }
                                                        }
                                                    } finally {
                                                        checkGray.release()
                                                    }
                                                }
                                            }

                                            accumulatedMs = System.currentTimeMillis() - pulseStartTime
                                        }
                                        lastLeftTargetPos = Point(
                                            leftTarget.x + leftTarget.width / 2,
                                            leftTarget.y + leftTarget.height / 2
                                        )
                                    } else {
                                        log("⚠️ left 按钮未缓存")
                                    }
                                }
                                else -> {
                                    lastLeftTargetPos = null
                                    lastRightTargetPos = null
                                    log("两侧都无 target → 短按按钮后重新查找 cursor")
                                    if (cachedRightPos != null) {
                                        doClick(cachedRightPos!!.x, cachedRightPos!!.y)
                                    } else if (cachedLeftPos != null) {
                                        doClick(cachedLeftPos!!.x, cachedLeftPos!!.y)
                                    }
                                }
                            }

                        } finally {
                            // 释放灰度 Mat（每次周期新建，此处释放）
                            cycleGray.release()
                        }
                    }

                    delayUntilCycleEnd(cycleStartMs, CYCLE_INTERVAL_STEP3_MS)
                } else {
                    // ══════════════════════════════════
                    // ③-2 refer 消失 → 查找 step4
                    // ══════════════════════════════════

                    val step4Rect = findElementWithCache(
                        templateName = "step4.jpg",
                        threshold = THRESHOLD_STEP4,
                        cachedRect = cachedStep4Rect,
                        defaultSearchArea = getStep4DefaultArea()
                    )

                    if (step4Rect != null) {
                        val step4Center = Point(
                            step4Rect.x + step4Rect.width / 2,
                            step4Rect.y + step4Rect.height / 2
                        )
                        log("✅ 找到 step4: (${step4Center.x}, ${step4Center.y}) → 点击并等待 step1")

                        // 保存 step4 点击位置
                        val step4ClickX = step4Center.x
                        val step4ClickY = step4Center.y

                        // 先点击一次触发 step4
                        doClick(step4ClickX, step4ClickY)

                        // 重置除 step4 外的所有缓存
                        cachedStep1Rect = null
                        cachedReferRect = null
                        cachedLeftPos = null
                        cachedRightPos = null
                        lastLeftTargetPos = null
                        lastRightTargetPos = null
                        isFirstLoop = true

                        // 进入循环：每 50ms 点击一次 step4，直到 step1 出现
                        setPhase("④ 循环点击 step4 等待 step1")

                        while (isRunning) {
                            val cycleStartMs = System.currentTimeMillis()

                            // 查找 step1
                            val newStep1Rect = findElementWithCache(
                                templateName = "step1.jpg",
                                threshold = THRESHOLD_STEP1,
                                cachedRect = null,
                                defaultSearchArea = getStep1DefaultArea()
                            )

                            if (newStep1Rect != null) {
                                log("✅ step1 出现！")
                                cachedStep1Rect = Rect(
                                    newStep1Rect.x, newStep1Rect.y,
                                    newStep1Rect.x + newStep1Rect.width,
                                    newStep1Rect.y + newStep1Rect.height
                                )
                                break
                            }

                            // step1 未出现 → 点击 step4 并等待
                            doClick(step4ClickX, step4ClickY)
                            log("👆 点击 step4（等待 step1 出现中）")
                            delayUntilCycleEnd(cycleStartMs, 50L)
                        }

                        if (!isRunning) break

                        loopCount++
                        callback?.onFishCountUpdate(loopCount)
                        log("✅ 第 ${loopCount} 轮完成（step4 触发）")

                        if (maxLoops > 0 && loopCount >= maxLoops) {
                            log("✅ 完成 ${maxLoops} 轮")
                            break@mainLoop
                        }

                        // step1 已缓存，继续主循环
                        continue@mainLoop
                    }

                    // 没找到 step4 → 检查 step1 是否直接出现（不计次数）
                    val step1Direct = findElementWithCache(
                        templateName = "step1.jpg",
                        threshold = THRESHOLD_STEP1,
                        cachedRect = null,
                        defaultSearchArea = getStep1DefaultArea()
                    )

                    if (step1Direct != null) {
                        log("✅ step1 出现（refer 消失后直接检测到）→ 从头循环，不计数")
                        cachedStep1Rect = Rect(
                            step1Direct.x, step1Direct.y,
                            step1Direct.x + step1Direct.width,
                            step1Direct.y + step1Direct.height
                        )
                        cachedReferRect = null
                        cachedLeftPos = null
                        cachedRightPos = null
                        cachedStep4Rect = null
                        lastLeftTargetPos = null
                        lastRightTargetPos = null
                        isFirstLoop = true
                        // 不增加 loopCount，不回调 onFishCountUpdate
                        continue@mainLoop
                    }

                    // 没找到 step4 或 step1 → 检查 refer 是否重新出现
                    val referReappeared = findElementWithCache(
                        templateName = "step3refer.jpg",
                        threshold = THRESHOLD_REFER,
                        cachedRect = cachedReferRect,
                        defaultSearchArea = getReferDefaultArea()
                    )

                    if (referReappeared != null) {
                        // refer 重新出现 → 切换到 ③-1
                        log("refer 重新出现 → 切换回跟踪模式")
                        inReferTracking = true
                        // referSearchArea 保持不重置（第一次找到 refer 时计算一次，永久复用）
                        // 更新 refer 缓存区域
                        cachedReferRect = Rect(
                            referReappeared.x, referReappeared.y,
                            referReappeared.x + referReappeared.width,
                            referReappeared.y + referReappeared.height
                        )
                        continue
                    }

                    // refer 没出现 → 快速等待后继续查找 step4
                    delayUntilCycleEnd(cycleStartMs, 50L)
                }
            }

            // 正常轮次完成（如果从 ③-1 正常退出而非 step4 触发）
            loopCount++
            callback?.onFishCountUpdate(loopCount)
            log("✅ 第 ${loopCount} 轮完成")

            if (maxLoops > 0 && loopCount >= maxLoops) {
                log("✅ 完成 ${maxLoops} 轮")
                break
            }

            delay(500)
        }

        log("⏹ 脚本结束")
    }

    // ══════════════════════════════════════════════
    // 等待首帧
    // ══════════════════════════════════════════════

    private suspend fun awaitFirstFrame(): Bitmap? {
        var frame = latestFrame
        var attempts = 0
        while ((frame == null || frame.isRecycled) && isRunning && attempts < 50) {
            attempts++
            delay(100)
            frame = latestFrame
        }
        if (frame == null || frame.isRecycled) {
            log("❌ 等待首帧超时")
            return null
        }
        log("📐 屏幕尺寸: ${frame.width}x${frame.height}")
        return frame
    }

    // ══════════════════════════════════════════════
    // 缓存 left/right 按钮位置
    // ══════════════════════════════════════════════

    private suspend fun cacheLeftRightButtons() {
        if (cachedLeftPos != null && cachedRightPos != null) return

        val screenW = getScreenWidth()
        val screenH = getScreenHeight()

        // left 按钮：左下角 (x < 1/3W, y > 1/2H)
        if (cachedLeftPos == null) {
            val leftArea = Rect(0, screenH / 2, screenW / 3, screenH)
            val leftResult = matchSingle("step3left.jpg", leftArea)
            if (leftResult != null) {
                cachedLeftPos = Point(leftResult.center.x, leftResult.center.y)
                log("📍 left 按钮: (${cachedLeftPos!!.x}, ${cachedLeftPos!!.y})")
            } else {
                log("⚠️ left 按钮未找到，按区域中心缓存")
                cachedLeftPos = Point(screenW / 6, screenH * 3 / 4)
            }
        }

        // right 按钮：右下角 (x > 2/3W, y > 1/2H)
        if (cachedRightPos == null) {
            val rightArea = Rect(screenW * 2 / 3, screenH / 2, screenW, screenH)
            val rightResult = matchSingle("step3right.jpg", rightArea)
            if (rightResult != null) {
                cachedRightPos = Point(rightResult.center.x, rightResult.center.y)
                log("📍 right 按钮: (${cachedRightPos!!.x}, ${cachedRightPos!!.y})")
            } else {
                log("⚠️ right 按钮未找到，按区域中心缓存")
                cachedRightPos = Point(screenW * 5 / 6, screenH * 3 / 4)
            }
        }
    }

    // ══════════════════════════════════════════════
    // 默认搜索区域定义
    // ══════════════════════════════════════════════

    /** step1 默认搜索区域：右下角 (x > 2/3W, y > 2/3H) */
    private fun getStep1DefaultArea(): Rect {
        val w = getScreenWidth()
        val h = getScreenHeight()
        return Rect(w * 2 / 3, h * 2 / 3, w, h)
    }

    /** refer 默认搜索区域：左上角 (x < 1/2W, y < 1/4H) */
    private fun getReferDefaultArea(): Rect {
        val w = getScreenWidth()
        val h = getScreenHeight()
        return Rect(0, 0, w / 2, h / 4)
    }

    /** step4 默认搜索区域：全屏搜索（根据实际需要调整） */
    private fun getStep4DefaultArea(): Rect? = null

    // ══════════════════════════════════════════════
    // 统一带缓存的元素查找
    // ══════════════════════════════════════════════

    /**
     * 查找元素，支持无缓存时区域搜索/有缓存时在矩形区域附近搜索。
     *
     * 缓存存储的是 OpenCV matchTemplate 返回的完整矩形（目标左上角坐标 + 宽高），
     * 而不是中心点。有缓存时，在缓存的矩形区域向四周扩展 NEARBY_OFFSET 的范围内搜索，
     * 这样才能覆盖元素可能的微小位移。
     *
     * 注意：NEARBY_OFFSET 可为任意整数（奇数/偶数均可），扩展计算只涉及整数加减，
     * 无需除法，不会产生取整问题。
     *
     * @param templateName 模板文件名
     * @param threshold 匹配阈值
     * @param cachedRect 缓存的矩形区域（null 表示未缓存过），使用 android.graphics.Rect(left, top, right, bottom)
     * @param defaultSearchArea 无缓存时的默认搜索区域（null 表示全屏）
     * @return 匹配到的矩形区域，或 null
     */
    private fun findElementWithCache(
        templateName: String,
        threshold: Double,
        cachedRect: Rect?,
        defaultSearchArea: Rect?
    ): org.opencv.core.Rect? {
        val safeFrame = captureFrame() ?: return null
        val template = loadTemplate(templateName) ?: return null

        val searchArea: Rect? = if (cachedRect != null) {
            // 有缓存 → 在缓存的矩形区域基础上向四周扩展 NEARBY_OFFSET 作为搜索范围
            // NEARBY_OFFSET 可为奇数/偶数（纯整数加减，无需除法，无取整问题）
            val screenW = getScreenWidth()
            val screenH = getScreenHeight()
            val l = (cachedRect.left - NEARBY_OFFSET).coerceIn(0, screenW)
            val t = (cachedRect.top - NEARBY_OFFSET).coerceIn(0, screenH)
            val r = (cachedRect.right + NEARBY_OFFSET).coerceIn(0, screenW)
            val b = (cachedRect.bottom + NEARBY_OFFSET).coerceIn(0, screenH)
            if (r <= l || b <= t) null
            else Rect(l, t, r, b)
        } else {
            defaultSearchArea
        }

        val result = try {
            ImageMatcher.matchTemplate(safeFrame, template, threshold, searchArea, null)
        } catch (e: Exception) {
            null
        } finally {
            safeFrame.recycle()
            // template is cached — do NOT recycle
        }
        return result?.rect
    }

    // ══════════════════════════════════════════════
    // refer 搜索区域计算（基于 refer 位置计算一次，后续复用）
    // ══════════════════════════════════════════════

    /**
     * 根据 refer 位置计算 cursor/target 的搜索区域。
     * 这个方法只在步骤③-1 首次需要时调用一次，后续复用缓存的 referSearchArea。
     */
    private fun calculateReferSearchArea(
        referCenterX: Int,
        referTop: Int,
        referBottom: Int,
    ): Rect {
        val screenW = getScreenWidth()
        val screenH = getScreenHeight()

        // 从 refer 区域右侧和中心位置计算 cursor 搜索区域
        // 宽度：屏幕宽度减去左右偏移，高度：refer 中间偏左部分
        val searchLeft = referCenterX + 77
        val searchRight = referCenterX + 873
        val searchTop = referTop + 10
        val searchBottom = referTop + 35

        val l = searchLeft.coerceIn(0, screenW)
        val r = searchRight.coerceIn(0, screenW)
        val t = searchTop.coerceIn(0, screenH)
        val b = searchBottom.coerceIn(0, screenH)

        return Rect(l, t, r, b)
    }

    // ══════════════════════════════════════════════
    // cursor 匹配
    // ══════════════════════════════════════════════

    /**
     * 在指定区域内搜索 cursor。
     * 搜索区域由调用方传入（首次使用大初始区域，后续使用固定的 referSearchArea）
     */
    private fun findCursorInArea(area: Rect): org.opencv.core.Rect? {
        val searchRect = area

        val searchW = searchRect.right - searchRect.left
        val searchH = searchRect.bottom - searchRect.top
        if (searchW <= 0 || searchH <= 0) return null

        val template = loadTemplate("step3cursor.jpg") ?: return null
        val safeFrame = captureFrame() ?: return null
        val result = try {
            ImageMatcher.matchTemplate(safeFrame, template, THRESHOLD_CURSOR, searchRect, null)
        } catch (e: Exception) {
            null
        } finally {
            safeFrame.recycle()
            // template is cached — do NOT recycle
        }

        return result?.rect
    }

    // ══════════════════════════════════════════════
    // target 匹配
    // ══════════════════════════════════════════════

    /**
     * 在固定搜索区域内，根据当前 cursor 位置动态分割左右区域搜索 target。
     *
     * 每次调用都根据 cursor 的实时位置重新计算左右区域：
     * - 左 target：从固定搜索区域左边界到 cursor 左边界
     * - 右 target：从 cursor 右边界到固定搜索区域右边界
     *
     * 不使用缓存，因为 cursor 每次移动后左右区域需要实时更新。
     *
     * @param isLeft true=搜索左侧，false=搜索右侧
     * @param cursorRect cursor 当前的矩形区域
     * @param searchArea 固定搜索区域（由 refer 计算确定）
     */
    private fun findTargetInSide(
        isLeft: Boolean,
        cursorRect: org.opencv.core.Rect,
        searchArea: Rect
    ): org.opencv.core.Rect? {
        // 每次根据 cursor 实时位置动态分割搜索区域
        val targetL: Int = searchArea.left
        val targetR: Int = searchArea.right

        // 横向范围根据 cursor 位置分割
        val actualL: Int
        val actualR: Int
        if (isLeft) {
            // 左 target：从固定区域左边界到 cursor 左边缘
            actualL = targetL
            actualR = cursorRect.x
        } else {
            // 右 target：从 cursor 右边缘到固定区域右边界
            actualL = cursorRect.x + cursorRect.width
            actualR = targetR
        }

        val actualW = actualR - actualL
        val actualH = searchArea.bottom - searchArea.top
        if (actualW <= 0 || actualH <= 0) return null

        val searchRect = Rect(actualL, searchArea.top, actualR, searchArea.bottom)

        val template = loadTemplate("step3target.jpg") ?: return null
        val safeFrame = captureFrame() ?: return null
        val result = try {
            ImageMatcher.matchTemplate(safeFrame, template, THRESHOLD_TARGET, searchRect, null)
        } catch (e: Exception) {
            null
        } finally {
            safeFrame.recycle()
            // template is cached — do NOT recycle
        }

        return result?.rect
    }

    // ══════════════════════════════════════════════
    // 单次匹配辅助（用于 button 缓存）
    // ══════════════════════════════════════════════

    private fun matchSingle(
        templateName: String,
        searchArea: Rect
    ): ImageMatcher.MatchResult? {
        val safeFrame = captureFrame() ?: return null
        val template = loadTemplate(templateName) ?: return null
        val result = try {
            ImageMatcher.matchTemplate(safeFrame, template, THRESHOLD_LEFT_RIGHT, searchArea, null)
        } catch (e: Exception) {
            null
        } finally {
            safeFrame.recycle()
            // template is cached — do NOT recycle
        }
        return result
    }

    // ══════════════════════════════════════════════
    // 屏幕尺寸
    // ══════════════════════════════════════════════

    private fun getScreenWidth(): Int {
        val frame = latestFrame
        return if (frame != null && !frame.isRecycled) frame.width else 2400
    }

    private fun getScreenHeight(): Int {
        val frame = latestFrame
        return if (frame != null && !frame.isRecycled) frame.height else 1080
    }

    // ══════════════════════════════════════════════
    // 点击操作
    // ══════════════════════════════════════════════

    private fun doClick(x: Int, y: Int) {
        if (AutoClickAccessibilityService.isConnected) {
            AutoClickAccessibilityService.performClick(x, y)
        } else {
            log("⚠️ 无障碍服务未连接")
        }
    }

    private fun doLongClick(x: Int, y: Int, durationMs: Long) {
        if (AutoClickAccessibilityService.isConnected) {
            AutoClickAccessibilityService.performLongClick(x, y, durationMs)
        } else {
            log("⚠️ 无障碍服务未连接")
        }
    }

    // ══════════════════════════════════════════════
    // Template 加载与缓存
    // ══════════════════════════════════════════════

    private fun loadTemplate(fileName: String): Bitmap? {
        templateCache[fileName]?.let { return it }
        return try {
            val stream = context.assets.open("$TEMPLATE_DIR/$fileName")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            templateCache[fileName] = bmp
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "加载模板失败: $fileName", e)
            null
        }
    }

    private fun clearTemplateCache() {
        templateCache.values.forEach { it.recycle() }
        templateCache.clear()
    }

    private fun resetPositionCache() {
        cachedStep1Rect = null
        cachedReferRect = null
        cachedLeftPos = null
        cachedRightPos = null
        cachedStep4Rect = null
        // referSearchArea 保持不重置（第一次找到 refer 时计算一次，永久复用）
        lastLeftTargetPos = null
        lastRightTargetPos = null
        isFirstLoop = true
        enterTrackingDirectly = false
    }

    private fun resetState() {
        resetPositionCache()
        loopCount = startingCount
        currentPhase = 0
        isPaused = false
        clearTemplateCache()
    }

    /**
     * 检测当前屏幕方向是否为横屏
     */
    private fun isLandscape(): Boolean {
        return try {
            val orientation = context.resources.configuration.orientation
            orientation == Configuration.ORIENTATION_LANDSCAPE
        } catch (e: Exception) {
            true  // 无法获取时默认为横屏，不阻断脚本
        }
    }

    /**
     * 等待横屏，如果竖屏则暂停等待最多 timeoutMs 毫秒。
     * @return true=横屏可继续, false=超时或停止
     */
    private suspend fun waitForLandscapeOrStop(timeoutMs: Long = 10000): Boolean {
        if (isLandscape()) return true
        
        val startTime = System.currentTimeMillis()
        isPaused = true
        log("⏸ 检测到竖屏，暂停执行，等待横屏...")
        
        while (isRunning) {
            if (isLandscape()) {
                isPaused = false
                log("▶️ 检测到横屏，继续执行")
                return true
            }
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                isPaused = false
                log("⏱ 竖屏超时${timeoutMs/1000}秒，停止脚本")
                stop()
                return false
            }
            delay(500)
        }
        isPaused = false
        return false
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        callback?.onLog(message)
    }
}
