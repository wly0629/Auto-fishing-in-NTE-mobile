package com.smallrong.autoclicker

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point as CvPoint
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 图像匹配引擎
 * 在屏幕截图中匹配模板图片，返回匹配位置
 */
object ImageMatcher {

    private const val TAG = "ImageMatcher"

    /**
     * 匹配结果
     * @param center 匹配到的中心点坐标（屏幕坐标）
     * @param confidence 匹配置信度 (0.0 ~ 1.0)
     * @param rect 匹配到的矩形区域
     */
    data class MatchResult(
        val center: Point,
        val confidence: Double,
        val rect: org.opencv.core.Rect
    )

    /**
     * 使用模板匹配在源图中查找模板（全屏搜索）
     *
     * @param screenBitmap 屏幕截图
     * @param templateBitmap 模板图片（要匹配的目标）
     * @param threshold 匹配阈值 (0.0 ~ 1.0)，低于此值的匹配结果会被忽略
     * @return 匹配结果，未找到返回 null
     */
    fun matchTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Double = 0.8
    ): MatchResult? {
        return matchTemplate(screenBitmap, templateBitmap, threshold, null, null)
    }

    /**
     * 使用模板匹配在源图中查找模板（支持限定搜索区域）
     *
     * @param screenBitmap 屏幕截图
     * @param templateBitmap 模板图片（要匹配的目标）
     * @param threshold 匹配阈值 (0.0 ~ 1.0)，低于此值的匹配结果会被忽略
     * @param searchArea 限定搜索区域（屏幕坐标），为 null 则全屏搜索
     * @return 匹配结果（坐标基于原始屏幕坐标），未找到返回 null
     */
    fun matchTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Double = 0.8,
        searchArea: Rect? = null
    ): MatchResult? {
        return matchTemplate(screenBitmap, templateBitmap, threshold, searchArea, null)
    }

    /**
     * 使用模板匹配在源图中查找模板（支持限定搜索区域 + mask）
     *
     * @param screenBitmap 屏幕截图
     * @param templateBitmap 模板图片（要匹配的目标）
     * @param threshold 匹配阈值 (0.0 ~ 1.0)，低于此值的匹配结果会被忽略
     * @param searchArea 限定搜索区域（屏幕坐标），为 null 则全屏搜索
     * @param maskBitmap mask 图片（非黑色区域参与匹配），为 null 则全模板匹配
     * @return 匹配结果（坐标基于原始屏幕坐标），未找到返回 null
     */
    fun matchTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Double = 0.8,
        searchArea: Rect? = null,
        maskBitmap: Bitmap? = null
    ): MatchResult? {
        val startTime = System.currentTimeMillis()

        // ── 从截图中截取搜索区域（若指定了 searchArea） ──
        val cropBitmap: Bitmap
        val cropOffsetX: Int  // 裁剪区域在原始屏幕中的 X 偏移
        val cropOffsetY: Int  // 裁剪区域在原始屏幕中的 Y 偏移

        if (searchArea != null) {
            // 确保不越界
            val safeLeft = searchArea.left.coerceIn(0, screenBitmap.width)
            val safeTop = searchArea.top.coerceIn(0, screenBitmap.height)
            val safeRight = searchArea.right.coerceIn(0, screenBitmap.width)
            val safeBottom = searchArea.bottom.coerceIn(0, screenBitmap.height)

            if (safeRight <= safeLeft || safeBottom <= safeTop) {
                Log.e(TAG, "搜索区域无效: $searchArea")
                return null
            }

            cropBitmap = Bitmap.createBitmap(screenBitmap, safeLeft, safeTop,
                safeRight - safeLeft, safeBottom - safeTop)
            cropOffsetX = safeLeft
            cropOffsetY = safeTop

            Log.d(TAG, "限定搜索区域: ($safeLeft, $safeTop, $safeRight, $safeBottom)")
        } else {
            cropBitmap = screenBitmap
            cropOffsetX = 0
            cropOffsetY = 0
        }

        // Bitmap → Mat
        val screenMat = Mat()
        val templateMat = Mat()
        Utils.bitmapToMat(cropBitmap, screenMat)
        Utils.bitmapToMat(templateBitmap, templateMat)

        // 释放裁剪的 Bitmap（若是裁剪产生的）
        if (searchArea != null) {
            cropBitmap.recycle()
        }

        // 模板尺寸不能大于搜索区域
        if (templateMat.cols() > screenMat.cols() || templateMat.rows() > screenMat.rows()) {
            Log.e(TAG, "模板尺寸(${templateMat.cols()}x${templateMat.rows()}) 大于搜索区域(${screenMat.cols()}x${screenMat.rows()})")
            releaseMats(screenMat, templateMat)
            return null
        }

        // 转换为灰度图以提高匹配速度
        val screenGray = Mat()
        val templateGray = Mat()
        Imgproc.cvtColor(screenMat, screenGray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_RGBA2GRAY)

        // 处理 mask（如果有的话）
        val maskGray: Mat? = if (maskBitmap != null) {
            val maskMat = Mat()
            Utils.bitmapToMat(maskBitmap, maskMat)
            val m = Mat()
            Imgproc.cvtColor(maskMat, m, Imgproc.COLOR_RGBA2GRAY)
            maskMat.release()
            // mask 中非零（非黑色）像素参与匹配
            m
        } else {
            null
        }

        // 模板匹配结果矩阵
        val resultCols = screenGray.cols() - templateGray.cols() + 1
        val resultRows = screenGray.rows() - templateGray.rows() + 1
        val result = Mat(resultRows, resultCols, CvType.CV_32FC1)

        // 使用 TM_CCOEFF_NORMED 方法（带或不带 mask）
        if (maskGray != null) {
            Imgproc.matchTemplate(screenGray, templateGray, result, Imgproc.TM_CCOEFF_NORMED, maskGray)
        } else {
            Imgproc.matchTemplate(screenGray, templateGray, result, Imgproc.TM_CCOEFF_NORMED)
        }

        // 找到最佳匹配位置（相对于裁剪区域的坐标）
        val minMaxResult = Core.minMaxLoc(result)
        val maxVal = minMaxResult.maxVal
        val maxLoc = minMaxResult.maxLoc

        // 释放 Mat
        releaseMats(screenMat, templateMat, screenGray, templateGray, result, maskGray)

        // TM_CCOEFF_NORMED 返回值范围 [-1, 1]
        // 负值表示反向相关，肯定不是匹配；低于阈值的也不匹配
        if (maxVal < 0.0 || maxVal < threshold) {
            return null
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "匹配完成: 置信度=${"%.4f".format(maxVal)}, " +
                "裁剪区位置=(${maxLoc.x}, ${maxLoc.y}), 耗时=${elapsed}ms")

        // 转换为原始屏幕坐标
        val screenX = (maxLoc.x + cropOffsetX).toInt()
        val screenY = (maxLoc.y + cropOffsetY).toInt()
        val centerX = screenX + templateBitmap.width / 2
        val centerY = screenY + templateBitmap.height / 2

        return MatchResult(
            center = Point(centerX, centerY),
            confidence = maxVal,
            rect = org.opencv.core.Rect(screenX, screenY, templateBitmap.width, templateBitmap.height)
        )
    }

    /**
     * 多目标匹配：找到所有超过阈值的匹配
     */
    fun matchTemplateMulti(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Double = 0.8,
        maxResults: Int = 10,
        searchArea: Rect? = null,
        maskBitmap: Bitmap? = null
    ): List<MatchResult> {
        val cropBitmap: Bitmap
        val cropOffsetX: Int
        val cropOffsetY: Int

        if (searchArea != null) {
            val safeLeft = searchArea.left.coerceIn(0, screenBitmap.width)
            val safeTop = searchArea.top.coerceIn(0, screenBitmap.height)
            val safeRight = searchArea.right.coerceIn(0, screenBitmap.width)
            val safeBottom = searchArea.bottom.coerceIn(0, screenBitmap.height)
            if (safeRight <= safeLeft || safeBottom <= safeTop) return emptyList()
            cropBitmap = Bitmap.createBitmap(screenBitmap, safeLeft, safeTop,
                safeRight - safeLeft, safeBottom - safeTop)
            cropOffsetX = safeLeft
            cropOffsetY = safeTop
        } else {
            cropBitmap = screenBitmap
            cropOffsetX = 0
            cropOffsetY = 0
        }

        val screenMat = Mat()
        val templateMat = Mat()
        Utils.bitmapToMat(cropBitmap, screenMat)
        Utils.bitmapToMat(templateBitmap, templateMat)

        if (searchArea != null) cropBitmap.recycle()

        val screenGray = Mat()
        val templateGray = Mat()
        Imgproc.cvtColor(screenMat, screenGray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_RGBA2GRAY)

        val resultCols = screenGray.cols() - templateGray.cols() + 1
        val resultRows = screenGray.rows() - templateGray.rows() + 1
        val result = Mat()
        if (maskBitmap != null) {
            val maskMat = Mat()
            Utils.bitmapToMat(maskBitmap, maskMat)
            val maskGray = Mat()
            Imgproc.cvtColor(maskMat, maskGray, Imgproc.COLOR_RGBA2GRAY)
            maskMat.release()
            Imgproc.matchTemplate(screenGray, templateGray, result, Imgproc.TM_CCOEFF_NORMED, maskGray)
            maskGray.release()
        } else {
            Imgproc.matchTemplate(screenGray, templateGray, result, Imgproc.TM_CCOEFF_NORMED)
        }

        val results = mutableListOf<MatchResult>()
        val mask = Mat(result.rows(), result.cols(), CvType.CV_8UC1, Scalar(1.0))

        for (i in 0 until maxResults) {
            val minMax = Core.minMaxLoc(result, mask)
            if (minMax.maxVal < threshold) break

            val loc = minMax.maxLoc
            val screenX = loc.x.toInt() + cropOffsetX
            val screenY = loc.y.toInt() + cropOffsetY

            results.add(MatchResult(
                center = Point(screenX + templateBitmap.width / 2, screenY + templateBitmap.height / 2),
                confidence = minMax.maxVal,
                rect = org.opencv.core.Rect(screenX, screenY, templateBitmap.width, templateBitmap.height)
            ))

            Imgproc.rectangle(
                mask,
                CvPoint(loc.x, loc.y),
                CvPoint(loc.x + templateBitmap.width, loc.y + templateBitmap.height),
                Scalar(0.0),
                -1
            )
        }

        releaseMats(screenMat, templateMat, screenGray, templateGray, result, mask)
        return results
    }

    private fun releaseMats(vararg mats: Mat?) {
        mats.forEach { it?.release() }
    }
}
