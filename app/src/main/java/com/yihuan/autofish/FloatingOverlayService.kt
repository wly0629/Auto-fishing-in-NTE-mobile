package com.yihuan.autofish

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.res.Configuration
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/**
 * 悬浮窗服务
 *
 * 在系统上方显示可拖动的悬浮控制面板，提供：
 * - 运行按钮：启动脚本
 * - 停止按钮：停止脚本
 * - 状态显示：当前脚本执行状态
 * - 关闭按钮：隐藏悬浮窗
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val CHANNEL_ID = "floating_overlay_channel"
        private const val NOTIFICATION_ID = 2001

        /** 请求 MainActivity 发起录屏权限申请的回调（替代广播，避免 RECEIVER_NOT_EXPORTED 兼容问题） */
        var onRequestScreenCapture: (() -> Unit)? = null

        /** 录屏权限获取成功后自动触发 onRunClicked 的回调（由 MainActivity 调用） */
        var onPermissionGranted: (() -> Unit)? = null

        /** 悬浮窗是否正在显示 */
        @Volatile
        var isShowing = false
            private set

        /** 点击脚本引擎引用（由 MainActivity 设置） */
        @Volatile
        var scriptEngine: ClickScript? = null
    }

    // ── 悬浮窗相关 ──

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI 组件
    private var btnRun: Button? = null
    private var btnStop: Button? = null
    private var tvStatus: TextView? = null
    private var tvFishCount: TextView? = null
    private var indicatorRunning: View? = null

    // 累计钓鱼计数（跨运行持久化）
    private var fishCount = 0

    // 帧缓冲区：ScreenCaptureService 产生帧时持续更新，引擎启动时直接取用
    @Volatile
    private var bufferedFrame: Bitmap? = null

    // 协程
    private val overlayScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 拖动相关
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        Log.d(TAG, "悬浮窗服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 只有在显式启动服务时才设置回调，避免 AccessibilityService 进程拉起时触发副作用
        // 早早设置帧回调 —— 在 ScreenCaptureService 启动前就位
        // 这样帧从一开始就被缓存，不会因为 engine 未创建而全部丢失
        // 注意：不 recycle 旧帧 —— ClickScript 的协程可能还在用旧帧做模板匹配，recycle 会导致 Native Crash
        ScreenCaptureService.onFrameCaptured = { bitmap ->
            bufferedFrame = bitmap
            // 同时转发给正在运行的引擎
            scriptEngine?.onFrameReceived(bitmap)
        }

        // 录屏权限获取后自动启动脚本
        onPermissionGranted = {
            val startTime = System.currentTimeMillis()
            mainHandler.post(object : Runnable {
                override fun run() {
                    if (ScreenCaptureService.isRunning) {
                        Log.d(TAG, "ScreenCaptureService 已就绪，耗时 ${System.currentTimeMillis() - startTime}ms")
                        onRunClicked()
                    } else if (System.currentTimeMillis() - startTime > 10000) {
                        Log.w(TAG, "等待录屏服务超时（10s），放弃自动启动")
                    } else {
                        mainHandler.postDelayed(this, 100) // 每 100ms 重试一次
                    }
                }
            })
        }

        // 前台服务通知 — Android 13+ 若未授权 POST_NOTIFICATIONS，通知静默不显示，服务仍正常运行
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "前台服务通知启动失败（可能无通知权限），服务继续运行", e)
        }

        if (!isShowing) {
            showOverlay()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // 悬浮窗被销毁时，确保脚本和录屏也停止
        if (scriptEngine?.isRunning == true) {
            scriptEngine?.stop()
            scriptEngine = null
            val intent = Intent(this, ScreenCaptureService::class.java)
            stopService(intent)
        }
        hideOverlay()
        MainActivity.floatingServiceStarted = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉悬浮窗通知时，确保脚本和录屏也停止
        if (scriptEngine?.isRunning == true) {
            scriptEngine?.stop()
            scriptEngine = null
            val intent = Intent(this, ScreenCaptureService::class.java)
            stopService(intent)
        }
        hideOverlay()
        MainActivity.floatingServiceStarted = false
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ══════════════════════════════════════════════
    // 悬浮窗显示/隐藏
    // ══════════════════════════════════════════════

    private fun showOverlay() {
        if (isShowing) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.floating_overlay, null)

        // 绑定 UI 组件
        btnRun = overlayView?.findViewById(R.id.btnRun)
        btnStop = overlayView?.findViewById(R.id.btnStop)
        tvFishCount = overlayView?.findViewById(R.id.tvFishCount)
        tvStatus = overlayView?.findViewById(R.id.tvScriptStatus)

        indicatorRunning = overlayView?.findViewById(R.id.indicatorRunning)
        val btnClose = overlayView?.findViewById<Button>(R.id.btnClose)

        // 按钮事件
        btnRun?.setOnClickListener { onRunClicked() }
        btnStop?.setOnClickListener { onStopClicked() }
        btnClose?.setOnClickListener { onCloseClicked() }

        // 拖动支持：让整个悬浮窗每个位置都能拖动
        // overlayView（根布局）处理文本区域和空白区域的拖动
        // 按钮同时保留点击和拖动能力
        overlayView?.setOnTouchListener(overlayDragListener)
        listOf(btnRun, btnStop, btnClose).forEach { btn ->
            btn?.setOnTouchListener(dragTouchListener)
        }

        // 设置悬浮窗参数
        val layoutFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 200
        }

        windowManager.addView(overlayView, params)
        isShowing = true
        updateUI()
        // 初始显示鱼计数
        tvFishCount?.text = "🎣 已钓到 0 条鱼"
        tvFishCount?.visibility = View.VISIBLE
        Log.d(TAG, "悬浮窗已显示")
    }

    private fun hideOverlay() {
        if (!isShowing) return

        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "移除悬浮窗失败", e)
        }

        overlayView = null
        btnRun = null
        btnStop = null
        tvStatus = null
        indicatorRunning = null
        isShowing = false
        Log.d(TAG, "悬浮窗已隐藏")
    }

    // ══════════════════════════════════════════════
    // 拖动与点击处理
    // ══════════════════════════════════════════════
    // 策略：每个按钮设置 OnTouchListener 处理拖动，同时保留 OnClickListener 处理点击。
    // 按钮 OnTouchListener 在 DOWN 返回 false（不消耗），让 OnClickListener 自然响应；
    // 在 MOVE 中检测是否超过拖动阈值，超过则移动悬浮窗并返回 true（消耗事件，取消点击）；
    // 在 UP 中如果拖动了则返回 true，否则返回 false（让 OnClickListener 触发）。
    // 这是 Android 悬浮窗中最可靠的点击+拖动共存方案，不依赖 getLocationOnScreen 等坐标映射。

    private val dragTouchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val ov = overlayView ?: return@OnTouchListener false
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                val params = ov.layoutParams as WindowManager.LayoutParams
                initialX = params.x
                initialY = params.y
                isDragging = false
                false  // 不消耗，让 OnClickListener 正常工作
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.rawX - initialTouchX)
                val dy = Math.abs(event.rawY - initialTouchY)
                if (!isDragging && (dx > 30 || dy > 30)) {
                    isDragging = true
                }
                if (isDragging) {
                    val ov = overlayView ?: return@OnTouchListener true
                    val p = ov.layoutParams as WindowManager.LayoutParams
                    p.x = initialX + (event.rawX - initialTouchX).toInt()
                    p.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(ov, p)
                    return@OnTouchListener true  // 消耗，取消点击
                }
                false
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    return@OnTouchListener true  // 是拖动，取消点击
                }
                false  // 是点击，OnClickListener 触发
            }
            else -> false
        }
    }

    // 悬浮窗整体拖动监听 — 让根布局（文本区域/空白区域）也能拖动
    private val overlayDragListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val ov = overlayView ?: return@OnTouchListener false
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                val params = ov.layoutParams as WindowManager.LayoutParams
                initialX = params.x
                initialY = params.y
                isDragging = false
                true  // 消耗事件，让根布局也能跟踪后续触摸
            }
            MotionEvent.ACTION_MOVE -> {
                val ov = overlayView ?: return@OnTouchListener true
                val p = ov.layoutParams as WindowManager.LayoutParams
                p.x = initialX + (event.rawX - initialTouchX).toInt()
                p.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(ov, p)
                isDragging = true
                true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                true
            }
            else -> false
        }
    }

    // ══════════════════════════════════════════════
    // 按钮事件
    // ══════════════════════════════════════════════

    /**
     * 运行按钮 — 先检验运行状态，已运行则提示；未运行则按序检测权限并启动脚本
     */
    private fun onRunClicked() {
        Log.d(TAG, "onRunClicked 触发")

        // 0. 已运行则提示，不再重复启动
        if (scriptEngine?.isRunning == true) {
            Toast.makeText(this, "程序已在运行", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. 检查并引导无障碍权限
        if (!AutoClickAccessibilityService.isConnected) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. 检查并引导悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }

        // 3. 横屏检测 — 在申请录屏权限前确保手机处于横屏状态
        if (!isLandscape()) {
            Toast.makeText(this, "请将手机横屏放置后再运行", Toast.LENGTH_SHORT).show()
            // 启动协程等待横屏（最多10秒）
            overlayScope.launch {
                val landscapeReady = awaitLandscape(10000)
                if (landscapeReady && isLandscape()) {
                    Log.d(TAG, "检测到横屏，继续执行")
                    mainHandler.post { onRunClicked() }
                } else {
                    Log.w(TAG, "等待横屏超时（10s），取消运行")
                    mainHandler.post {
                        Toast.makeText(this@FloatingOverlayService, "等待横屏超时，已取消运行", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        // 4. 录屏权限 — 未授权或服务未运行，直接弹出系统授权界面（不跳转 MainActivity）
        val prefs = getSharedPreferences("autoclicker", MODE_PRIVATE)
        if (!prefs.getBoolean("screen_capture_authorized", false) || !ScreenCaptureService.isRunning) {
            // 启动透明的 ScreenCaptureActivity 请求权限，用户无感知，不跳转主页面
            val authIntent = Intent(this, ScreenCaptureActivity::class.java)
            authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(authIntent)
            return
        }

        // 6. 再次检验运行状态（权限获取过程中可能已通过其他路径启动）
        if (scriptEngine?.isRunning == true) {
            Toast.makeText(this, "程序已在运行", Toast.LENGTH_SHORT).show()
            return
        }

        // 7. 如果 scriptEngine 为空则初始化
        if (scriptEngine == null) {
            scriptEngine = ClickScript(applicationContext, overlayScope).apply {
                startingCount = fishCount
                callback = object : ClickScript.Callback {
                    override fun onStarted() {
                        mainHandler.post { updateUI() }
                    }
                    override fun onStopped() {
                        mainHandler.post { stopScriptAndRecording() }
                    }
                    override fun onPhaseChanged(phase: String) {
                        Log.d(TAG, "phase: $phase")
                    }
                    override fun onLog(message: String) {
                        Log.d(TAG, message)
                        AppLogger.log(message)
                    }
                    override fun onFishCountUpdate(count: Int) {
                        fishCount = count
                        mainHandler.post { updateFishCount(count) }
                    }
                }
            }
        }

        val engine = scriptEngine ?: return

        // 把已缓存的帧喂给引擎，避免 runPositioning 从头等帧
        bufferedFrame?.let { buf ->
            if (!buf.isRecycled) {
                engine.onFrameReceived(buf)
            }
        }

        // 权限全部就绪，启动脚本（计数从上次累计值继续，不归零）
        engine.start()
        Toast.makeText(this, "程序开始执行", Toast.LENGTH_SHORT).show()
        updateUI()
        Log.d(TAG, "脚本启动")
    }

    /**
     * 停止按钮 — 真实检验脚本+录屏运行状态，在则停止，不在则提示
     */
    private fun onStopClicked() {
        Log.d(TAG, "onStopClicked 触发")
        val engineRunning = scriptEngine?.isRunning == true
        val screenCaptureRunning = ScreenCaptureService.isRunning

        if (!engineRunning && !screenCaptureRunning) {
            Toast.makeText(this, "程序未运行", Toast.LENGTH_SHORT).show()
            return
        }
        stopScriptAndRecording()
        Toast.makeText(this, "程序已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 关闭悬浮窗 — 停止脚本和录屏（如果在运行），然后关闭悬浮窗服务
     */
    private fun onCloseClicked() {
        Log.d(TAG, "onCloseClicked 触发")
        val engineRunning = scriptEngine?.isRunning == true
        val screenCaptureRunning = ScreenCaptureService.isRunning
        if (engineRunning || screenCaptureRunning) {
            stopScriptAndRecording()
        }
        stopSelf()
    }

    /**
     * 检查当前屏幕是否为横屏
     */
    private fun isLandscape(): Boolean {
        val orientation = resources.configuration.orientation
        return orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * 在协程中等待屏幕变为横屏
     * @param timeoutMs 超时毫秒数
     * @return true=在超时前变横屏, false=超时
     */
    private suspend fun awaitLandscape(timeoutMs: Long = 10000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isLandscape()) return true
            delay(100)
        }
        return false
    }

    /**
     * 停止脚本引擎并停止录屏服务
     */
    private fun stopScriptAndRecording() {
        // 停止脚本引擎
        scriptEngine?.stop()
        scriptEngine = null
        // 停止录屏服务
        if (ScreenCaptureService.isRunning) {
            val intent = Intent(this, ScreenCaptureService::class.java)
            stopService(intent)
            Log.d(TAG, "录屏服务已停止")
        }
        updateUI()
        Log.d(TAG, "脚本和录屏已停止")
    }

    // ══════════════════════════════════════════════
    // UI 更新
    // ══════════════════════════════════════════════

    private fun updateFishCount(count: Int) {
        mainHandler.post {
            tvFishCount?.text = "🎣 已钓到 $count 条鱼"
            tvFishCount?.visibility = View.VISIBLE
        }
    }

    private fun updateUI() {
        val engine = scriptEngine
        val running = engine?.isRunning ?: false

        btnRun?.isEnabled = !running
        // 停止按钮始终保持可点击，确保 OnClickListener 总能触发；
        // onStopClicked 内部会检验实际运行状态再决定是否停止
        btnStop?.isEnabled = true

        // 更新状态文字
        val statusText = when {
            engine == null -> ""
            running -> "运行中"
            else -> ""
        }
        tvStatus?.text = statusText

        // 更新指示灯
        val indicatorDrawable = indicatorRunning?.background
        if (indicatorDrawable is android.graphics.drawable.GradientDrawable) {
            indicatorDrawable.setColor(
                if (running) 0xFF4CAF50.toInt() else 0xFF808090.toInt()
            )
        }
    }

    // ══════════════════════════════════════════════
    // Notification（前台服务通知）
    // ══════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮窗控制面板服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("智能点击助手")
            .setContentText("悬浮窗控制面板运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
