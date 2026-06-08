package com.smallrong.autoclicker

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
        private const val THRESHOLD_CURSOR = 0.93


        /** target 模板匹配阈值 — target 图像干净，极高置信度 */
        private const val THRESHOLD_TARGET = 0.97


        /** left/right 按钮模板匹配阈值 */
        private const val THRESHOLD_LEFT_RIGHT = 0.80


        /** step4 模板匹配阈值 */
        private const val THRESHOLD_STEP4 = 0.70

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
        // 其他可调参数
        // ═══════════════════════════════════════════════════════════════
        /** 所有元素的"附近"搜索偏移量（X/Y ± 此值），用于有缓存位置时的窄范围搜索 */
        private const val NEARBY_OFFSET = 2
        /** 移动速度（像素/毫秒），用于计算长按时间 = 距离 / 速度 */
        private const val SPEED = 0.33

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

    // 左右 target 最后已知位置
    private var lastLeftTargetPos: Point? = null
    private var lastRightTargetPos: Point? = null

    // 步骤状态标识
    private var isFirstLoop = true

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
     * 线程安全地捕获当前帧的深拷贝。
     *
     * 注意：deepCopyBitmap 调用在 synchronized 块外部执行，
     * 避免在主线程 onFrameReceived 试图写入 latestFrame 时被锁阻塞。
     * synchronized 仅用于读取 latestFrame 的引用，不包含耗时的像素拷贝。
     */
    private fun captureFrame(): Bitmap? {
        val frame = synchronized(frameLock) { latestFrame }
        return frame?.let { if (it.isRecycled) null else deepCopyBitmap(it) }
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

        mainLoop@ while (isRunning) {
            // 每轮开始检查屏幕方向，竖屏时等待最多10秒
            if (!waitForLandscapeOrStop()) break

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
            delay(LOOP_WAIT_AFTER_STEP1_CLICK_MS)

            // ═══════════════════════════════════════════
            // ② 连续点击 step1，查找 refer，同时首次记录 left/right
            // ═══════════════════════════════════════════
            if (!isRunning) break
            setPhase("② 循环点击 step1 等待 refer")

            var referRect: org.opencv.core.Rect?
            var step1ClickCount = 0

            do {
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

                if (referRect == null) {
                    delay(LOOP_WAIT_STEP2_CYCLE_MS)
                }
            } while (referRect == null && isRunning)

            if (!isRunning) break
            log("✅ refer 在 ${step1ClickCount} 次 step1 点击后出现")

            // 缓存 refer 矩形区域
            cachedReferRect = Rect(referRect!!.x, referRect!!.y, referRect!!.x + referRect!!.width, referRect!!.y + referRect!!.height)
            log("📌 缓存 refer 区域: (${cachedReferRect!!.left},${cachedReferRect!!.top})-(${cachedReferRect!!.right},${cachedReferRect!!.bottom}) 尺寸=${referRect!!.width}x${referRect!!.height}")

            // ═══════════════════════════════════════════
            // ③ 根据 refer 存在与否执行两种子循环
            // ═══════════════════════════════════════════
            if (!isRunning) break
            setPhase("③ 动态 target 跟踪")

            val trackingStartTime = System.currentTimeMillis()
            var inReferTracking = true   // true = ③-1, false = ③-2
            // referSearchArea 首次找到 refer 时计算一次，后续永久复用
            while (isRunning) {
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

                    // 检查 refer 是否仍然可见（使用附近搜索）
                    val currentReferRect = findElementWithCache(
                        templateName = "step3refer.jpg",
                        threshold = THRESHOLD_REFER,
                        cachedRect = cachedReferRect,
                        defaultSearchArea = getReferDefaultArea()
                    )

                    if (currentReferRect == null) {
                        // refer 消失了 → 切换到 ③-2
                        log("refer 消失 → 切换到 step4 搜索")
                        inReferTracking = false
                        continue
                    }

                    // 计算 refer 搜索区域（仅计算一次，后续复用）
                    if (referSearchArea == null) {
                        referSearchArea = calculateReferSearchArea(
                            currentReferRect.x + currentReferRect.width / 2,
                            currentReferRect.y,
                            currentReferRect.y + currentReferRect.height
                        )
                        log("📐 计算 refer 搜索区域完成")
                    }

                    val area = referSearchArea!!
                    if (area.left >= area.right || area.top >= area.bottom) {
                        log("⚠️ refer 搜索区域无效，重新计算")
                        referSearchArea = calculateReferSearchArea(
                            currentReferRect.x + currentReferRect.width / 2,
                            currentReferRect.y,
                            currentReferRect.y + currentReferRect.height
                        )
                        continue
                    }

                    // 查找 cursor
                    val cursorRect = findCursorInArea(area)
                    if (cursorRect == null) {
                        delay(LOOP_WAIT_FIND_CURSOR_MS)
                        continue
                    }

                    val cursorCenterX = cursorRect.x + cursorRect.width / 2

                    // 检查 cursor 左侧是否有 target
                    val leftTarget = findTargetInSide(
                        isLeft = true,
                        cursorRect = cursorRect,
                        searchArea = area
                    )
                    // 检查 cursor 右侧是否有 target
                    val rightTarget = findTargetInSide(
                        isLeft = false,
                        cursorRect = cursorRect,
                        searchArea = area
                    )

                    when {
                        leftTarget != null && rightTarget != null -> {
                            // 左右都有 target → 存储位置，不做操作
                            lastLeftTargetPos = Point(
                                leftTarget.x + leftTarget.width / 2,
                                leftTarget.y + leftTarget.height / 2
                            )
                            lastRightTargetPos = Point(
                                rightTarget.x + rightTarget.width / 2,
                                rightTarget.y + rightTarget.height / 2
                            )
                            log("⏸ 左右都有 target，等待...")
                            delay(LOOP_WAIT_STEP3_CYCLE_MS)
                        }
                        rightTarget != null -> {
                            // 只有右侧有 target → 长按 right 按钮
                            val rightTargetCenterX = rightTarget.x + rightTarget.width / 2
                            // 长按时间 = (cursor位置 - 左target位置) / 速度
                            val leftTargetX = lastLeftTargetPos?.x
                                ?: (area.left + cursorCenterX) / 2  // 近似
                            val holdMs = abs(cursorCenterX - leftTargetX) / SPEED
                            val holdMsLong = holdMs.toLong().coerceAtLeast(130L)
                            log("🔴 仅右侧有 target → 长按 RIGHT ${holdMsLong}ms " +
                                "(cursor=$cursorCenterX, leftTarget=$leftTargetX)")
                            cachedRightPos?.let { doLongClick(it.x, it.y, holdMsLong) }
                                ?: log("⚠️ right 按钮未缓存")

                            // 存储右 target 位置
                            lastRightTargetPos = Point(
                                rightTarget.x + rightTarget.width / 2,
                                rightTarget.y + rightTarget.height / 2
                            )
                            delay(LOOP_WAIT_STEP3_CYCLE_MS)
                        }
                        leftTarget != null -> {
                            // 只有左侧有 target → 长按 left 按钮
                            val leftTargetCenterX = leftTarget.x + leftTarget.width / 2
                            // 长按时间 = (右target位置 - cursor位置) / 速度
                            val rightTargetX = lastRightTargetPos?.x
                                ?: (cursorCenterX + area.right) / 2  // 近似
                            val holdMs = abs(rightTargetX - cursorCenterX) / SPEED
                            val holdMsLong = holdMs.toLong().coerceAtLeast(130L)
                            log("🔴 仅左侧有 target → 长按 LEFT ${holdMsLong}ms " +
                                "(cursor=$cursorCenterX, rightTarget=$rightTargetX)")
                            cachedLeftPos?.let { doLongClick(it.x, it.y, holdMsLong) }
                                ?: log("⚠️ left 按钮未缓存")

                            // 存储左 target 位置
                            lastLeftTargetPos = Point(
                                leftTarget.x + leftTarget.width / 2,
                                leftTarget.y + leftTarget.height / 2
                            )
                            delay(LOOP_WAIT_STEP3_CYCLE_MS)
                        }
                        else -> {
                            // 左右都没有 target → 跳出循环，重新查找 cursor
                            lastLeftTargetPos = null
                            lastRightTargetPos = null
                            log("两侧都无 target → 重新查找 cursor")
                            // 继续循环，会重新找 cursor
                        }
                    }
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

                        // 进入循环：每 200ms 点击一次 step4，直到 step1 出现
                        setPhase("④ 循环点击 step4 等待 step1")

                        while (isRunning) {
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
                            delay(LOOP_WAIT_FIND_STEP1_MS)
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

                    // refer 没出现 → 等待后继续查找 step4
                    delay(LOOP_WAIT_FIND_STEP4_MS)
                }

                // 跟踪循环内的正常 delay（针对 ③-1 场景，findCursor 和 target 检查用）
                // 如果没有 delay 且左右无 target 的场景，给一个循环间隔
                if (inReferTracking) {
                    // 已经在上面的分支里处理了 delay，不需要额外 delay
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
