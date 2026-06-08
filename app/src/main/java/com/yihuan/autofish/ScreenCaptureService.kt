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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 屏幕截图采集服务
 * 使用 MediaProjection API 持续截取屏幕
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"

        private const val VIRTUAL_DISPLAY_NAME = "AutoClickerCapture"

        /** 屏幕采集回调 */
        var onFrameCaptured: ((Bitmap) -> Unit)? = null

        /** 服务是否正在运行 */
        @Volatile
        var isRunning = false
            private set

        /** ADB授权后为true，录屏权限已就绪 */
        @Volatile
        var hasCapturePermission = false

        /** 通过 companion object 传递 resultCode+data，避免 Intent 序列化丢失 ClipData */
        private var pendingResultCode = -1
        private var pendingData: Intent? = null

        fun setPendingAuth(resultCode: Int, data: Intent) {
            Log.d(TAG, "setPendingAuth called: resultCode=$resultCode")
            pendingResultCode = resultCode
            pendingData = data
        }

        fun consumePendingAuth(): Pair<Int, Intent?> {
            val pair = Pair(pendingResultCode, pendingData)
            pendingResultCode = -1
            pendingData = null
            return pair
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        screenWidth = intent?.getIntExtra(EXTRA_WIDTH, 1080) ?: 1080
        screenHeight = intent?.getIntExtra(EXTRA_HEIGHT, 1920) ?: 1920
        screenDensity = intent?.getIntExtra(EXTRA_DENSITY, 320) ?: 320

        // 从 companion object 读取授权数据（避免 Intent 传递 ClipData 序列化丢失）
        val (resultCode, data) = consumePendingAuth()
        Log.d(TAG, "consumePendingAuth: resultCode=$resultCode, data=${data != null}")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (resultCode != -1 || data == null) {
            Log.e(TAG, "缺少 MediaProjection 授权数据 (resultCode=$resultCode, data=${data != null})")
            stopSelf()
            return START_NOT_STICKY
        }

        // 获取 MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        Log.d(TAG, "getMediaProjection 结果: ${mediaProjection != null}")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection 被系统停止")
                stopCapture()
            }
        }, mainHandler)

        // 立即启动采集（延迟创建反而导致 MediaProjection 被系统回收）
        val captureOk = startCapture()
        if (!captureOk) {
            Log.e(TAG, "屏幕采集启动失败，服务退出")
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        hasCapturePermission = true

        // 持久化标记 — ADB 授权后永久记住
        getSharedPreferences("autoclicker", MODE_PRIVATE)
            .edit()
            .putBoolean("screen_capture_authorized", true)
            .apply()

        return START_STICKY
    }

    private fun startCapture(): Boolean {
        val mp = mediaProjection ?: return false

        // 通过 WindowManager 获取真实屏幕尺寸（不受 Activity 方向影响）
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val windowMetrics = wm.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val realWidth = bounds.width()
        val realHeight = bounds.height()
        val density = resources.displayMetrics.densityDpi

        // 如果 Intent 传的尺寸与真实屏幕不匹配（如旋转后），使用真实尺寸
        val captureWidth = if (realWidth > 0) realWidth else screenWidth
        val captureHeight = if (realHeight > 0) realHeight else screenHeight
        screenWidth = captureWidth
        screenHeight = captureHeight
        screenDensity = density

        Log.i(TAG, "真实屏幕: ${realWidth}x${realHeight}, dpi=$density (Intent传入: ${screenWidth}x${screenHeight})")

        // 创建 ImageReader 用于接收屏幕帧
        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
            PixelFormat.RGBA_8888, 2
        )

        // 创建虚拟显示器
        virtualDisplay = mp.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            captureWidth, captureHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            imageReader?.surface,
            null, null
        )

        var frameCount = 0
        var lastFrameTimeMs = 0L
        val frameIntervalMs = 83L  // ~12 FPS，节省 CPU/内存

        // 监听每一帧
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }

            image?.use { img ->
                frameCount++
                if (frameCount == 1) {
                    Log.i(TAG, "首帧尺寸: ${img.width}x${img.height}, format=${img.format}")
                }
                // 跳过前 3 帧（黑屏/弹窗过渡），从第 4 帧起交付
                if (frameCount <= 3) {
                    if (frameCount == 3) {
                        Log.i(TAG, "第3帧: 跳过预热帧结束，后续帧正常交付")
                    }
                    return@use
                }

                // 帧率限制：每 frameIntervalMs 最多产出一帧
                val now = System.currentTimeMillis()
                if (now - lastFrameTimeMs < frameIntervalMs) {
                    return@use
                }
                lastFrameTimeMs = now

                val bitmap = imageToBitmap(img)
                bitmap?.let { onFrameCaptured?.invoke(it) }
            }
        }, mainHandler)

        Log.i(TAG, "屏幕采集已启动: ${captureWidth}x${captureHeight}, dpi=$density")
        return true
    }

    private var pixelDebugDone = false

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val imageWidth = image.width
        val imageHeight = image.height
        val actualPixelsPerRow = rowStride / pixelStride

        // 检测维度是否被翻转（某些设备 ImageReader 报告了错误的宽/高）
        val useWidth: Int
        val useHeight: Int
        if (actualPixelsPerRow != imageWidth && pixelStride >= 3) {
            // 实际每行像素数与 image.width 不匹配，使用实际像素数作为宽度
            // 这表示 image.width/image.height 可能被翻转了
            useWidth = actualPixelsPerRow
            useHeight = (buffer.capacity() / rowStride).coerceAtMost(imageHeight * imageWidth / actualPixelsPerRow)
            Log.w(TAG, "维度不匹配！image.width=$imageWidth, image.height=$imageHeight, " +
                    "actualPixelsPerRow=$actualPixelsPerRow, buffer.capacity()=${buffer.capacity()}")
            Log.i(TAG, "修正为: ${useWidth}x$useHeight")
        } else {
            useWidth = imageWidth
            useHeight = imageHeight
        }

        // 逐像素深拷贝，完全切断与 ImageReader 硬件缓冲区的关联
        val pixelCount = useWidth * useHeight
        val pixels = IntArray(pixelCount)
        val buf = buffer.duplicate()
        buf.order(ByteOrder.LITTLE_ENDIAN)  // 确保 little-endian 字节序

        var nonZeroCount = 0
        for (y in 0 until useHeight) {
            for (x in 0 until useWidth) {
                val idx = y * useWidth + x
                val pxIdx = y * rowStride + x * pixelStride
                if (pxIdx + 3 < buf.limit()) {
                    // 使用 getInt() 按 little-endian 读取 4 字节：
                    //   byte[0]=B, byte[1]=G, byte[2]=R, byte[3]=A
                    //   int = A<<24 | R<<16 | G<<8 | B  (正确 ARGB_8888)
                    // 避免手动拼 R/G/B/A 因字节序导致红蓝通道互换
                    val pixel = buf.getInt(pxIdx)
                    pixels[idx] = pixel
                    if ((pixel and 0x00FFFFFF) != 0) nonZeroCount++
                }
            }
        }

        if (!pixelDebugDone) {
            pixelDebugDone = true
            val centerIdx = (useWidth / 2) + (useHeight / 2) * useWidth
            val samplePixel = pixels[centerIdx.coerceIn(0, pixelCount - 1)]
            val r = (samplePixel shr 16) and 0xFF
            val g = (samplePixel shr 8) and 0xFF
            val b = samplePixel and 0xFF
            val totalPixels = useWidth * useHeight
            Log.i(TAG, "截屏诊断: ${useWidth}x${useHeight}, " +
                    "pixelStride=$pixelStride, rowStride=$rowStride, " +
                    "bufferCap=${buffer.capacity()}, " +
                    "非零像素=$nonZeroCount/$totalPixels (${(nonZeroCount * 100.0 / totalPixels).toInt()}%), " +
                    "中心像素 RGB=($r,$g,$b)")
        }

        return Bitmap.createBitmap(pixels, useWidth, useHeight, Bitmap.Config.ARGB_8888)
    }

    private fun stopCapture() {
        isRunning = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "屏幕采集已停止")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ──── Notification ────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕录制服务通知"
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
