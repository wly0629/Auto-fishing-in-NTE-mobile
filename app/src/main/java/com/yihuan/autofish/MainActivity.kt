package com.yihuan.autofish

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.View
import com.yihuan.autofish.BuildConfig
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.azhon.appupdate.manager.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method

/**
 * 主界面 — AutoClicker 权限与图片导入面板
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PACKAGE_NAME = "com.yihuan.autofish"

        /** 悬浮窗服务是否已启动 — 供 FloatingOverlayService 关闭时重置 */
        @Volatile
        var floatingServiceStarted = false

        /** 是否从悬浮窗发起的录屏权限请求（静态变量，避免 Activity 重建后丢失） */
        @Volatile
        var permissionFromOverlay = false
    }

    // 读取相册权限启动器
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickStep1Image()
        } else {
            Toast.makeText(this, "❌ 相册读取权限被拒绝，无法导入图片", Toast.LENGTH_LONG).show()
        }
    }

    /** 抑制 setOnCheckedChangeListener 回调 — 程序化设置开关时不触发 ADB 弹窗 */
    private var suppressSwitchCallback = false

    private val myHandler = Handler(Looper.getMainLooper())

    // 录屏权限启动器（MediaProjection）— 弹出系统授权界面
    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val prefs = getSharedPreferences("autoclicker", MODE_PRIVATE)
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 权限获取成功
            ScreenCaptureService.setPendingAuth(result.resultCode, result.data!!)
            prefs.edit().putBoolean("screen_capture_authorized", true).apply()
            // 程序化设置开关，不触发监听器里的 ADB 弹窗
            suppressSwitchCallback = true
            switchScreenRecord.isChecked = true
            suppressSwitchCallback = false
            tvAdbHint.visibility = View.GONE
            // 立即启动录屏服务，确保 MediaProjection 被实际使用
            val intent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // 通知悬浮窗权限已就绪，自动启动脚本
            permissionFromOverlay = false
            FloatingOverlayService.onPermissionGranted?.invoke()

            // 延迟检查录屏服务是否真正启动，若未启动则提示用户
            myHandler.postDelayed({
                if (!ScreenCaptureService.isRunning) {
                    Toast.makeText(this, "录屏服务启动失败，请重试或使用 ADB 授权", Toast.LENGTH_LONG).show()
                }
            }, 3000)
        } else {
            if (permissionFromOverlay) {
                // 从悬浮窗发起的请求 → 不跳转主界面，只给简单提示
                permissionFromOverlay = false
                Toast.makeText(this, "请选择「录制整个屏幕」或使用 ADB 授权", Toast.LENGTH_SHORT).show()
            } else {
                // 从主界面发起的请求 → 引导用户 ADB 授权
                switchScreenRecord.isChecked = false
                prefs.edit().putBoolean("screen_capture_authorized", false).apply()
                tvAdbHint.visibility = View.VISIBLE
                showAdbCommandDialog()
            }
        }
    }

    private lateinit var switchScreenRecord: SwitchCompat
    private lateinit var tvAdbHint: android.widget.TextView

    // 图片选择启动器（分两步：先选 step1，再选 step3）
    private var pendingStep = 1 // 1=选step1图, 2=选step3图
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImageSelected(uri)
        } else {
            Toast.makeText(this, "已取消选择", Toast.LENGTH_SHORT).show()
            pendingStep = 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupCards()

        // 显示版本号（只显示版本名）
        findViewById<android.widget.TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"

        // 设置悬浮窗录屏权限回调 — 点击运行按钮时通过此回调弹出录屏授权界面
        FloatingOverlayService.onRequestScreenCapture = {
            permissionFromOverlay = true
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }


        // 延迟检查更新（不阻塞界面加载）
        myHandler.postDelayed({ checkUpdate() }, 2000)
    }

    private fun setupCards() {
        // 控件 1: 无障碍权限
        findViewById<androidx.cardview.widget.CardView>(R.id.cardAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请在列表中找到「智能点击助手」并开启「无障碍」开关", Toast.LENGTH_LONG).show()
        }

        // 控件 2: 悬浮窗权限 — 有权限直接开启，无权限跳转设置
        findViewById<androidx.cardview.widget.CardView>(R.id.cardOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$PACKAGE_NAME")
                )
                startActivity(intent)
                Toast.makeText(this, "请开启后自动开启悬浮窗", Toast.LENGTH_LONG).show()
            } else {
                startFloatingService()
            }
        }

        // 控件 3: 录屏权限 — Switch 开关检测 ADB 授权
        switchScreenRecord = findViewById(R.id.switchScreenRecord)
        tvAdbHint = findViewById(R.id.tvAdbHint)

        // 如果之前已有持久化标记，直接显示为开启
        val prefs = getSharedPreferences("autoclicker", MODE_PRIVATE)
        if (prefs.getBoolean("screen_capture_authorized", false)) {
            switchScreenRecord.isChecked = true
        }

        switchScreenRecord.setOnCheckedChangeListener { _, isChecked ->
            // 程序化设置开关时跳过，避免触发 ADB 弹窗
            if (suppressSwitchCallback) return@setOnCheckedChangeListener

            if (isChecked) {
                // 用户打开开关 → 先用 AppOpsManager 检查 ADB 授权，不弹任何窗口
                if (checkScreenCapturePermission()) {
                    // ADB 已授权 → 持久化标记，保持开关开启
                    prefs.edit().putBoolean("screen_capture_authorized", true).apply()
                    tvAdbHint.visibility = View.GONE
                    Toast.makeText(this, "✅ 检测到 ADB 授权，录屏权限已就绪", Toast.LENGTH_SHORT).show()
                } else {
                    // 未授权 → 复位开关，显示 ADB 提示
                    switchScreenRecord.isChecked = false
                    prefs.edit().putBoolean("screen_capture_authorized", false).apply()
                    tvAdbHint.visibility = View.VISIBLE
                    showAdbCommandDialog()
                }
            } else {
                // 用户关闭开关 → 清除持久化标记
                prefs.edit().putBoolean("screen_capture_authorized", false).apply()
                tvAdbHint.visibility = View.GONE
            }
        }

        // 控件 4: 导入图片
        findViewById<androidx.cardview.widget.CardView>(R.id.cardImportImage).setOnClickListener {
            requestImagePermissionAndPick()
        }

        // 控件 5: 绘制测试（原「测试」，查看图像识别匹配区域）
        findViewById<androidx.cardview.widget.CardView>(R.id.cardTest).setOnClickListener {
            val intent = Intent(this, TestActivity::class.java)
            startActivity(intent)
        }

        // 控件 6: 识别点击测试（验证无障碍点击坐标）
        findViewById<androidx.cardview.widget.CardView>(R.id.cardClickTest).setOnClickListener {
            val intent = Intent(this, ClickTestActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * 显示 ADB 命令弹窗（无需真的申请录屏权限）
     */
    private fun showAdbCommandDialog() {
        val adbCommand = "adb shell appops set $PACKAGE_NAME PROJECT_MEDIA allow"

        val message = buildString {
            appendLine("📺 录屏权限说明")
            appendLine()
            appendLine("请通过 ADB 命令授予永久权限：")
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine(adbCommand)
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("在已连接电脑/无线调试的情况下，")
            appendLine("在终端中执行以上命令即可。")
            appendLine()
            appendLine("完成后无需每次都申请录屏权限。")
        }

        AlertDialog.Builder(this)
            .setTitle("📺 录屏权限")
            .setMessage(message)
            .setPositiveButton("复制命令") { _, _ ->
                // 复制到剪贴板
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("ADB Command", adbCommand)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✅ 已复制 ADB 命令到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("了解") { _, _ -> }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 通过反射调用 AppOpsManager.checkOp 检查 PROJECT_MEDIA (op 46) 是否已授权。
     * ADB 命令 "adb shell appops set <pkg> PROJECT_MEDIA allow" 会设为 MODE_ALLOWED。
     * 无需任何 UI 交互，静默检测。兼容 Android 9 ~ 16。
     */
    private fun checkScreenCapturePermission(): Boolean {
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
            // PROJECT_MEDIA 的操作码是 46
            val opCode = 46
            // 反射调用 hidden 方法 checkOp(int op, int uid, String packageName)
            val checkOpMethod: Method = AppOpsManager::class.java.getDeclaredMethod(
                "checkOp", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, String::class.java
            )
            checkOpMethod.isAccessible = true
            val result = checkOpMethod.invoke(appOps, opCode, Process.myUid(), packageName) as Int
            // MODE_ALLOWED = 0, MODE_IGNORED = 1, MODE_ERRORED = 2, MODE_DEFAULT = 3
            result == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "checkScreenCapturePermission failed", e)
            false
        }
    }

    /**
     * 申请相册权限，成功后开始两步选图流程
     */
    private fun requestImagePermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pickStep1Image()
        } else if (shouldShowRequestPermissionRationale(permission)) {
            AlertDialog.Builder(this)
                .setTitle("需要读取相册")
                .setMessage("请选择两张钓鱼界面的示例图片，用于替换内置模板进行匹配测试。")
                .setPositiveButton("去授权") { _, _ ->
                    permissionLauncher.launch(permission)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    /**
     * 第一步：选择 step1 示例图片
     */
    private fun pickStep1Image() {
        pendingStep = 1
        AlertDialog.Builder(this)
            .setTitle("选择 step1 示例图（第1张/共2张）")
            .setMessage("请选择一张钓鱼界面的示例图片，将用于替换内置的 step1 模板。")
            .setPositiveButton("选择图片") { _, _ ->
                imagePickerLauncher.launch("image/*")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 第二步：选择 step3 示例图片
     */
    private fun pickStep3Image() {
        pendingStep = 2
        AlertDialog.Builder(this)
            .setTitle("选择 step3 示例图（第2张/共2张）")
            .setMessage("请再选择一张钓鱼界面的示例图片，将用于替换内置的 step3 模板。")
            .setPositiveButton("选择图片") { _, _ ->
                imagePickerLauncher.launch("image/*")
            }
            .setNegativeButton("取消") { _, _ -> pendingStep = 1 }
            .show()
    }

    /**
     * 处理选中的图片：按顺序保存为 step1example1.jpg / step3example1.jpg
     */
    private fun handleImageSelected(uri: Uri) {
        try {
            when (pendingStep) {
                1 -> {
                    // 保存为 step1example1.jpg
                    val file = File(filesDir, "step1example1.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    Toast.makeText(this, "✅ step1 示例图已保存", Toast.LENGTH_SHORT).show()

                    // 延迟一下再弹出第二步对话框
                    Handler(Looper.getMainLooper()).postDelayed({
                        pickStep3Image()
                    }, 500)
                }

                2 -> {
                    // 保存为 step3example1.jpg
                    val file = File(filesDir, "step3example1.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    Toast.makeText(this, "✅ step3 示例图已保存，正在打开测试页面…", Toast.LENGTH_SHORT).show()
                    pendingStep = 1

                    // 启动测试页面
                    val intent = Intent(this, TestActivity::class.java)
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 保存图片失败: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "save image failed", e)
        }
    }

    override fun onResume() {
        super.onResume()

        // 更新无障碍和悬浮窗权限指示灯
        updateIndicator(R.id.permIndicator1, isAccessibilityServiceEnabled())
        updateIndicator(R.id.permIndicator2,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))

        // 更新录屏权限状态（开关只反映 ADB 授权状态）
        val prefs = getSharedPreferences("autoclicker", MODE_PRIVATE)
        val actualPermission = checkScreenCapturePermission()
        if (actualPermission) {
            // ADB 授权 → 永久标记
            prefs.edit().putBoolean("screen_capture_authorized", true).apply()
            suppressSwitchCallback = true
            switchScreenRecord.isChecked = true
            suppressSwitchCallback = false
        } else {
            // 无 ADB 授权
            suppressSwitchCallback = true
            switchScreenRecord.isChecked = false
            suppressSwitchCallback = false
        }
        tvAdbHint.visibility = View.GONE
    }

    /**
     * 检查远程更新（启动后延迟2秒调用）
     * 从远程 update.json 获取版本信息，使用 AppUpdate 库展示更新弹窗
     */
    private fun checkUpdate() {
        val updateUrl = "https://raw.githubusercontent.com/wly0629/yihuan-autofish-android/main/update.json"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonStr = fetchUrl(updateUrl)
                val obj = JSONObject(jsonStr)
                val remoteVersionCode = obj.optInt("versionCode", 0)
                val remoteVersionName = obj.optString("versionName", "")
                val apkUrl = obj.optString("apkUrl", "")
                val updateLog = obj.optString("updateLog", "暂无更新说明")
                val apkSize = obj.optString("size", "0MB")

                // 只在远程版本更新时才弹窗
                if (remoteVersionCode > BuildConfig.VERSION_CODE && apkUrl.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("发现新版本 v$remoteVersionName")
                            .setMessage(updateLog)
                            .setPositiveButton("立即更新") { _, _ ->
                                try {
                                    val manager = DownloadManager.Builder(this@MainActivity).run {
                                        apkUrl(apkUrl)
                                        apkName("autofish.apk")
                                        smallIcon(R.mipmap.ic_launcher)
                                        apkVersionCode(remoteVersionCode)
                                        apkVersionName(remoteVersionName)
                                        apkSize(apkSize)
                                        apkDescription(updateLog)
                                        showNotification(true)
                                        build()
                                    }
                                    manager.download()
                                } catch (e: Exception) {
                                    Log.e(TAG, "启动更新组件失败", e)
                                }
                            }
                            .setNegativeButton("稍后再说", null)
                            .show()
                    }
                } else {
                    Log.d(TAG, "当前已是最新版本 (${BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新失败", e)
            }
        }
    }

    /**
     * 简易 HTTP GET 请求，获取 URL 返回的文本内容
     */
    private fun fetchUrl(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        return conn.inputStream.bufferedReader().readText()
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    /**
     * 开启悬浮窗服务 — 仅在用户点击「开启悬浮窗」按钮后调用
     */
    private fun startFloatingService() {
        if (!floatingServiceStarted) {
            floatingServiceStarted = true
            startFloatingServiceInternal()
        } else {
            Toast.makeText(this, "悬浮窗已在运行", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFloatingServiceInternal() {
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FloatingOverlayService::class.java)
            startForegroundService(intent)
            Toast.makeText(this, "✅ 悬浮窗已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能开启", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 更新所有权限指示灯的显示状态
     */
    private fun updateIndicator(id: Int, on: Boolean) {
        val indicator = findViewById<View>(id)
        indicator?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (on) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#666666")
        )
    }

    /**
     * 检查无障碍服务是否已开启
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${
            AutoClickAccessibilityService::class.java.canonicalName
        }"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
        } catch (e: Exception) {
            return false
        }
    }
}
