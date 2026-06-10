package com.yihuan.autofish

/**
 * 全局日志缓冲区 — 用于主页面日志查看栏实时显示运行日志
 */
object AppLogger {
    private val maxLines = 200
    private val buffer = mutableListOf<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun log(message: String) {
        synchronized(buffer) {
            buffer.add(message)
            if (buffer.size > maxLines) {
                buffer.removeAt(0)
            }
            val text = buffer.joinToString("\n")
            listener?.invoke(text)
        }
    }

    fun getText(): String {
        synchronized(buffer) {
            return buffer.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            listener?.invoke("")
        }
    }
}
