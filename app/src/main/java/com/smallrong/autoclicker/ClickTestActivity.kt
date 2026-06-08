package com.smallrong.autoclicker

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.*

/**
 * 识别点击测试 — 全屏显示测试图片，监听悬浮窗的点击/长按操作
 *
 * 使用方式:
 * 1. 开启悬浮窗进入此页面
 * 2. 点击悬浮窗的运行按钮
 * 3. 脚本执行时，点击/长按坐标会通过 Logcat 输出 (Tag: ClickTest)
 * 4. 点击 3 次后自动切换至 step3 测试图片
 *
 * Logcat 筛选: ClickTest 或 ClickTestActivity
 */
class ClickTestActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ClickTestActivity"
    }

    private lateinit var imageView: ImageView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val matchingScope = CoroutineScope(Dispatchers.IO)

    /** 透明点击目标 View，切换页面时需移除 */
    private var clickTargetView: View? = null

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 权限获取成功
            ScreenCaptureService.setPendingAuth(result.resultCode, result.data!!)
            getSharedPreferences("autoclicker", MODE_PRIVATE)
                .edit()
                .putBoolean("screen_capture_authorized", true)
                .apply()
            // 启动录屏服务
            val intent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // 通知悬浮窗权限已就绪
            FloatingOverlayService.onPermissionGranted?.invoke()
        } else {
            Toast.makeText(this, "请选择「录制整个屏幕」或使用 ADB 授权", Toast.LENGTH_SHORT).show()
        }
    }

    /** 0=step1, 1=step3 */
    private var currentPage = 0
    private var lastKnownClickCount = -1

    private val pollRunnable = object : Runnable {
        override fun run() {
            val currentCount = ClickEventMonitor.clickCount

            // 有新点击事件
            if (currentCount > lastKnownClickCount) {
                val x = ClickEventMonitor.lastX
                val y = ClickEventMonitor.lastY
                val type = if (ClickEventMonitor.lastActionType == 0) "点击" else "长按"

                Log.i(TAG, "▸ $type: ($x, $y) | 总计 ${currentCount} 次")

                // 点击 3 次后从 step1 切换到 step3
                if (currentPage == 0 && currentCount >= 3) {
                    switchToPage1() // step3 = page 1
                }
                lastKnownClickCount = currentCount
            }

            mainHandler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_click_test)

        imageView = findViewById(R.id.clickTestImage)

        // 沉浸式全屏 — 隐藏导航栏 + 状态栏
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        // 加载 step1 图片
        loadStep1Image()

        // 设置录屏权限回调 — 让悬浮窗运行按钮能弹出录屏授权界面
        FloatingOverlayService.onRequestScreenCapture = {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        Log.i(TAG, "══════════════════════════════════════")
        Log.i(TAG, "识别点击测试已启动 — 请在悬浮窗点击『运行』")
        Log.i(TAG, "Logcat 筛选: ${TAG} 或 ClickEventMonitor")
        Log.i(TAG, "══════════════════════════════════════")
    }

    override fun onResume() {
        super.onResume()
        // 重置计数并开始轮询
        lastKnownClickCount = ClickEventMonitor.clickCount
        mainHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        matchingScope.cancel()
    }

    // ── 图片加载 ──

    private fun loadStep1Image() {
        val bitmap = try {
            val userFile = File(filesDir, "step1example1.jpg")
            if (userFile.exists()) {
                Log.i(TAG, "加载用户图片: step1example1.jpg")
                BitmapFactory.decodeFile(userFile.absolutePath)
            } else {
                val stream = assets.open("${ClickScript.TEMPLATE_DIR}/step1example1.jpg")
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 step1 图片失败", e)
            null
        }

        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            Log.i(TAG, "已加载: step1example1.jpg (${bitmap.width}x${bitmap.height})")
            // 运行模板匹配 → 在匹配位置放置透明点击监听控件
            runStep1MatchingAndPlaceTarget(bitmap)
        } else {
            Log.e(TAG, "step1example1.jpg 加载失败")
        }
        currentPage = 0
    }

    private fun switchToPage1() {
        clearClickTarget()  // 切换页面时移除旧的透明点击目标
        currentPage = 1
        val bitmap = try {
            val userFile = File(filesDir, "step3example1.jpg")
            if (userFile.exists()) {
                Log.i(TAG, "加载用户图片: step3example1.jpg")
                BitmapFactory.decodeFile(userFile.absolutePath)
            } else {
                val stream = assets.open("${ClickScript.TEMPLATE_DIR}/step3example1.jpg")
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 step3 图片失败", e)
            null
        }

        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            Log.i(TAG, "━━━ 已切换至 step3example1.jpg (${bitmap.width}x${bitmap.height}) ━━━")
            Log.i(TAG, "继续监听点击和长按…")
        } else {
            Log.e(TAG, "step3example1.jpg 加载失败")
        }
    }

    // ── 透明点击目标 ──

    /** 移除之前放置的透明点击目标 */
    private fun clearClickTarget() {
        clickTargetView?.let { target ->
            val parent = target.parent as? ViewGroup
            parent?.removeView(target)
        }
        clickTargetView = null
    }

    /** 加载 step1 模板 → 匹配 → 在匹配位置放置透明点击监听控件 */
    private fun runStep1MatchingAndPlaceTarget(screenBitmap: Bitmap) {
        matchingScope.launch {
            if (!OpenCVHelper.isInitialized()) {
                OpenCVHelper.init()
            }

            val w = screenBitmap.width
            val h = screenBitmap.height
            val searchArea = Rect(w * 2 / 3, h * 2 / 3, w, h)

            val template = loadTemplateFromAssets("step1.jpg") ?: return@launch
            val mask = loadTemplateFromAssets("step1mask.jpg")

            val matches = ImageMatcher.matchTemplateMulti(
                screenBitmap = screenBitmap,
                templateBitmap = template,
                threshold = 0.90,
                maxResults = 20,
                searchArea = searchArea,
                maskBitmap = mask
            )

            template.recycle()
            mask?.recycle()

            val best = matches.maxByOrNull { it.confidence }

            withContext(Dispatchers.Main) {
                if (best != null) {
                    val pct = "%.1f".format(best.confidence * 100)
                    Log.i(TAG, "🎯 step1匹配: center=(${best.center.x},${best.center.y}) conf=${pct}% rect=(${best.rect.x},${best.rect.y},${best.rect.width}x${best.rect.height})")
                    placeClickTarget(screenBitmap.width, screenBitmap.height, best)
                } else {
                    Log.w(TAG, "⚠️ step1未匹配到目标，无法放置点击监听控件")
                }
            }
        }
    }

    private fun loadTemplateFromAssets(name: String): Bitmap? {
        return try {
            val stream = assets.open("${ClickScript.TEMPLATE_DIR}/$name")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "加载模板 $name 失败", e)
            null
        }
    }

    /** 在 ImageView 上放置一个透明 View，覆盖在匹配到的控件位置 */
    private fun placeClickTarget(bitmapW: Int, bitmapH: Int, match: ImageMatcher.MatchResult) {
        clearClickTarget()

        imageView.post {
            if (currentPage != 0) return@post  // 已经切换页面的不操作

            val viewW = imageView.width
            val viewH = imageView.height
            if (viewW <= 0 || viewH <= 0) return@post

            // fitCenter 缩放比例和偏移
            val scale = minOf(viewW.toFloat() / bitmapW, viewH.toFloat() / bitmapH)
            val offsetX = (viewW - bitmapW * scale) / 2f
            val offsetY = (viewH - bitmapH * scale) / 2f

            val screenX = offsetX + match.rect.x * scale
            val screenY = offsetY + match.rect.y * scale
            val screenW = match.rect.width * scale
            val screenH = match.rect.height * scale

            val targetView = View(this@ClickTestActivity).apply {
                isClickable = true
                isFocusable = false
                setBackgroundColor(Color.TRANSPARENT)

                setOnClickListener {
                    Log.i(TAG, "🎯 目标位置被点击! bitmap(${match.center.x},${match.center.y}) screen(${(screenX + screenW / 2).toInt()},${(screenY + screenH / 2).toInt()})")
                }
            }

            val params = FrameLayout.LayoutParams(
                maxOf(1, screenW.toInt()),
                maxOf(1, screenH.toInt())
            ).apply {
                leftMargin = screenX.toInt()
                topMargin = screenY.toInt()
            }

            (imageView.parent as ViewGroup).addView(targetView, params)
            clickTargetView = targetView

            Log.i(TAG, "🟢 已放置透明点击目标 (bitmap区域: ${match.rect.x},${match.rect.y} ${match.rect.width}x${match.rect.height})")
            Log.i(TAG, "   → screen位置: (${screenX.toInt()},${screenY.toInt()}) size=${screenW.toInt()}x${screenH.toInt()}")
        }
    }
}
