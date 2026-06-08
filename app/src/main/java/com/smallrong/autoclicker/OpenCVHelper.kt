package com.smallrong.autoclicker

import android.util.Log

/**
 * OpenCV 初始化工具类
 *
 * 使用 System.loadLibrary 直接加载 APK 内嵌的 libopencv_java4.so，
 * 不依赖外部 OpenCV Manager App。
 */
object OpenCVHelper {

    private const val TAG = "OpenCVHelper"
    private var initialized = false

    /**
     * 加载 APK 内嵌的 OpenCV 原生库（不需要 OpenCV Manager）
     * @return true 如果加载+初始化成功
     */
    fun init(): Boolean {
        if (initialized) {
            Log.d(TAG, "OpenCV 已经初始化过了")
            return true
        }

        return try {
            // 直接加载打包进 APK 的 libopencv_java4.so
            System.loadLibrary("opencv_java4")

            initialized = true
            Log.i(TAG, "OpenCV ${org.opencv.core.Core.VERSION} 初始化成功 (内嵌库)")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "OpenCV 加载失败: 找不到 libopencv_java4.so", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV 初始化异常", e)
            false
        }
    }

    /**
     * 检查是否已经初始化
     */
    fun isInitialized(): Boolean = initialized
}
