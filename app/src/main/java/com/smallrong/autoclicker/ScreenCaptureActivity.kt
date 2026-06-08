package com.smallrong.autoclicker

import androidx.activity.ComponentActivity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 透明的 Activity，仅用于从悬浮窗请求 MediaProjection 录屏权限。
 * 避免跳转到 MainActivity 主页面，用户无感知完成授权。
 */
class ScreenCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenCaptureActivity"
    }

    // 使用 registerForActivityResult 替代已弃用的 startActivityForResult
    // 兼容 Android 14+，避免 onActivityResult 不被回调
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d(TAG, "录屏权限获取成功")

            // 保存授权标记
            val prefs = getSharedPreferences("autoclicker", MODE_PRIVATE)
            prefs.edit().putBoolean("screen_capture_authorized", true).apply()

            // 设置 MediaProjection 授权数据
            ScreenCaptureService.setPendingAuth(result.resultCode, result.data!!)

            // 启动录屏服务
            val intent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // 通知悬浮窗权限已就绪（轮询等待，不固定延迟）
            FloatingOverlayService.onPermissionGranted?.invoke()

            // 权限授予成功后将任务退到后台，保持 task 存活，避免系统切换到桌面
            // 由于 taskAffinity=""，此 Activity 在独立 Task 中运行，不影响 MainActivity
            moveTaskToBack(true)
        } else {
            Log.d(TAG, "录屏权限被拒绝")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: 启动录屏权限请求")

        // 全面屏：不预留任何系统栏空间（状态栏、导航栏）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 权限结果回调中（而非 onCreate 中）调用 moveTaskToBack(true)，
        // 此时已获取到权限结果，将 Activity 退到后台不会影响回调。
        // 由于 taskAffinity=""，此 Activity 在独立 Task 中运行，不影响 MainActivity 的显示

        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 如果已经授权且录屏服务正在运行，退到后台保持任务存活
        val prefs = getSharedPreferences("autoclicker", MODE_PRIVATE)
        if (prefs.getBoolean("screen_capture_authorized", false) && ScreenCaptureService.isRunning) {
            moveTaskToBack(true)
            return
        }
        // 否则重新发起录屏权限请求
        Log.d(TAG, "onNewIntent: 重新发起录屏权限请求")
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
