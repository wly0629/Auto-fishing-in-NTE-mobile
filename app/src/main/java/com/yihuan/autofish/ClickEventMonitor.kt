package com.yihuan.autofish

/**
 * 点击事件监控 — 供 ClickTestActivity 读取 AccessbilityService 的点击状态
 *
 * AutoClickAccessibilityService 执行 doClick/doLongClick 时会更新此监控；
 * ClickTestActivity 通过轮询获取点击次数和坐标，实现点击三次自动切换图片。
 *
 * Logcat 筛选: ClickTest
 */
object ClickEventMonitor {
    /** 总的点击次数（含长按） */
    @Volatile
    var clickCount = 0

    /** 上一次点击的 X 坐标 */
    @Volatile
    var lastX = -1

    /** 上一次点击的 Y 坐标 */
    @Volatile
    var lastY = -1

    /** 0=点击, 1=长按 */
    @Volatile
    var lastActionType = 0
}
