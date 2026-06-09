package com.yihuan.autofish

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍自动点击服务
 * 使用 AccessibilityService 执行自动点击（无需 root）
 */
class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"

        /** 全局单例引用，供外部调用点击 */
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        /** 是否已连接 */
        val isConnected: Boolean get() = instance != null

        /**
         * 执行点击（静态方法，方便外部调用）
         * @return true 如果点击请求已发送
         */
        fun performClick(x: Int, y: Int): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "无障碍服务未连接，无法执行点击")
                return false
            }
            service.doClick(x, y)
            return true
        }

        /**
         * 执行长按
         */
        fun performLongClick(x: Int, y: Int, durationMs: Long = 800): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "无障碍服务未连接，无法执行长按")
                return false
            }
            service.doLongClick(x, y, durationMs)
            return true
        }

        /**
         * 执行滑动
         */
        fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "无障碍服务未连接，无法执行滑动")
                return false
            }
            service.doSwipe(startX, startY, endX, endY, durationMs)
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理事件，仅用于执行手势
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        super.onDestroy()
    }

    // ──── 手势执行 ────

    private fun doClick(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "手势 API 需要 Android 7.0+")
            return
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, null, null)
        Log.d(TAG, "执行点击: ($x, $y)")

        // 通知 ClickTestActivity 监控（先记录，即使 Toast 失败）
        ClickEventMonitor.clickCount++
        ClickEventMonitor.lastX = x
        ClickEventMonitor.lastY = y
        ClickEventMonitor.lastActionType = 0
        android.util.Log.i("ClickTest", "点击: ($x, $y) | 总 ${ClickEventMonitor.clickCount} 次")
    }

    private fun doLongClick(x: Int, y: Int, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "手势 API 需要 Android 7.0+")
            return
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, null, null)
        Log.d(TAG, "执行长按: ($x, $y) ${durationMs}ms")

        // 通知 ClickTestActivity 监控（先记录，即使 Toast 失败）
        ClickEventMonitor.clickCount++
        ClickEventMonitor.lastX = x
        ClickEventMonitor.lastY = y
        ClickEventMonitor.lastActionType = 1
        android.util.Log.i("ClickTest", "长按: ($x, $y) | ${durationMs}ms | 总 ${ClickEventMonitor.clickCount} 次")
    }

    private fun doSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "手势 API 需要 Android 7.0+")
            return
        }

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, null, null)
        Log.d(TAG, "执行滑动: ($startX, $startY) → ($endX, $endY)")
    }
}
