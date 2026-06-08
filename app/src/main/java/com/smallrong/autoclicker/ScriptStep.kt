package com.smallrong.autoclicker

import android.graphics.Rect

/** 脚本步骤定义 — 单个匹配-点击步骤的配置 */
data class ScriptStep(
    val templateFileName: String,
    val clickOffsetX: Int = 0,
    val clickOffsetY: Int = 0,
    val matchThreshold: Double = 0.6,
    val timeoutMs: Long = 30000L,
    val description: String = "",
    val searchArea: Rect? = null,
    val maskFileName: String? = null,
    val skipClick: Boolean = false
)
