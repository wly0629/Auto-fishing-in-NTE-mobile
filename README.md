# 异环手游自动钓鱼 🐟

> 基于 OpenCV 模板匹配 + Android AccessibilityService 的异环（NTE）手游自动钓鱼工具。
>
> **免 Root、免 ADB、免 PC**，纯手机端运行。

[![GitHub release](https://img.shields.io/github/v/release/wly0629/yihuan-autofish-android)](https://github.com/wly0629/yihuan-autofish-android/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## 📱 快速开始

1. 从 [Releases](https://github.com/wly0629/yihuan-autofish-android/releases) 下载最新 APK
2. 安装到手机，依次授予以下权限：
   - **无障碍服务** → 开启「异环自动钓鱼」开关
   - **悬浮窗权限** → 允许在其他应用上层显示
   - **屏幕录制权限** → 授权后开始采集画面
3. 打开异环手游，进入钓鱼界面
4. 点击悬浮窗的 **▶ 运行** 按钮，自动开始钓鱼循环

> 💡 首次建议先打开「开发者模式」（主界面连续点击版本号 7 次），在测试页确认模板能正确匹配。

---

## 🎣 工作原理

| 步骤 | 说明 |
|------|------|
| **① 甩竿** | 在屏幕右下角区域匹配 `step1` 模板，找到后点击 |
| **② 等待上钩** | 持续监测 `refer` 确认按钮是否出现 |
| **③ 拉杆** | `refer` 出现后，匹配光标（cursor）与目标区域（target），自动长按 left/right 按钮将光标拉入目标 |
| **④ 循环** | 拉杆完成后查找 `step4`，回到步骤①持续循环 |

整个流程全自动，无需人工干预。

---

## 🏗️ 项目结构

```
AutoFish/
├── app/
│   └── src/main/java/com/yihuan/autofish/
│       ├── MainActivity.kt                   ← 主界面 UI（权限管理、图片导入、开发者模式）
│       ├── ClickScript.kt                    ← 🎯 核心钓鱼脚本引擎（渐进式匹配 + 动态跟踪）
│       ├── ImageMatcher.kt                   ← OpenCV 模板匹配引擎
│       ├── FloatingOverlayService.kt         ← 悬浮控制面板服务
│       ├── ScreenCaptureActivity.kt          ← 录屏权限申请（透明 Activity）
│       ├── ScreenCaptureService.kt            ← 屏幕截图采集服务（MediaProjection）
│       ├── AutoClickAccessibilityService.kt   ← 无障碍自动点击服务
│       ├── ClickEventMonitor.kt              ← 点击事件监控
│       ├── ClickTestActivity.kt              ← 绘制测试页面（多步选择 + 左右滑动切换）
│       ├── TestActivity.kt                   ← 调试测试页面（显示最高匹配度）
│       ├── ScriptStep.kt                     ← 脚本步骤数据类
│       ├── OpenCVHelper.kt                   ← OpenCV 初始化辅助
│       └── AppLogger.kt                      ← 运行日志缓冲区
├── app/src/main/assets/scripts/              ← 🖼️ 模板图片目录
│       ├── step1.jpg / step1example1.jpg     ── 甩竿按钮
│       ├── step3refer.jpg / step3example1.jpg─ refer 确认按钮
│       ├── step3cursor.jpg                   ── 光标指针
│       ├── step3target.jpg                   ── 目标区域
│       ├── step3left.jpg / step3right.jpg    ── 左拉/右拉按钮
│       ├── step4.jpg / step4example1.jpg     ── 拉杆完成
│       ├── wechat_qr.png / alipay_qr.png     ── 支持作者收款码
│       └── README.md                         ── 模板裁剪参考文档
├── opencv/                                   ← OpenCV SDK 模块
├── update.json                               ← 远程更新配置
├── build.bat                                 ← 构建脚本
├── autofish-release.keystore                 ← APK 签名密钥
└── README.md                                 ← 本文件
```

---

## ⚙️ 核心参数

所有参数集中在 `ClickScript.kt` `companion object` 中，可直接修改。

### 匹配阈值

| 目标 | 值 | 说明 |
|------|-----|------|
| `THRESHOLD_STEP1` | 0.80 | 甩竿按钮匹配 |
| `THRESHOLD_REFER` | 0.80 | refer 确认按钮匹配 |
| `THRESHOLD_CURSOR` | 0.80 | 光标匹配 |
| `THRESHOLD_TARGET` | 0.90 | 目标区域匹配（图像干净，较高值） |
| `THRESHOLD_LEFT_RIGHT` | 0.80 | left/right 按钮匹配 |
| `THRESHOLD_STEP4` | 0.80 | step4 拉杆完成匹配 |

### 循环等待时间

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `LOOP_WAIT_FIND_STEP1_MS` | 200ms | 找不到 step1 重试间隔 |
| `STEP1_POST_CLICK_DELAY_MS` | 6000ms | 点击甩竿后等待 refer 出现 |
| `STEP2_CYCLE_MS` | 200ms | step2 循环间隔 |
| `STEP3_CYCLE_MS` | 250ms | step3 拉杆循环间隔 |
| `STEP3_POST_LONG_CLICK_DELAY_MS` | 500ms | 长按后的等待时间 |
| `CURSOR_NOT_FOUND_RETRY_DELAY_MS` | 1000ms | cursor 未找到重试间隔 |
| `STEP4_CHECK_INTERVAL_MS` | 500ms | step4 检测间隔 |
| `REFER_REAPPEAR_CHECK_INTERVAL_MS` | 1000ms | refer 重现检查间隔 |

### 光标跟踪参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `SPEED` | 0.31 | 光标移动速度系数 |
| `NEARBY_OFFSET` | 150px | 缓存位置附近搜索范围 |
| `SEARCH_BAR_WIDTH_RATIO` | 0.4 | refer 搜索条宽度比例 |
| `SEARCH_BAR_HEIGHT_RATIO` | 0.12 | refer 搜索条高度比例 |

### 搜索区域

| 目标 | 区域（相对屏幕宽度 W / 高度 H） |
|------|------|
| step1（甩竿） | 右下 (2/3W, 2/3H) → (W, H) |
| refer（确认） | 左上 (0, 0) → (1/2W, 1/4H) |
| left 按钮 | 左下 (0, 1/2H) → (1/3W, H) |
| right 按钮 | 右下 (2/3W, 1/2H) → (W, H) |
| step4 | 左上 (0, 0) → (1/2W, 1/4H) |

---

## 🧪 开发者模式

在主界面 **连续点击版本号 7 次** 即可进入开发者模式（伴随振动反馈），解锁以下功能：

- 📋 **运行日志面板** — 实时查看脚本执行日志
- 🖼️ **导入图片** — 支持选择匹配步骤（step1~step4、cursor、refer、target、left/right）
- 🧪 **测试页** — 显示当前屏幕与模板的最高匹配度（不限阈值）
- 📐 **绘制测试** — 多步选择 + 左右滑动切换页面

---

## 📝 版本历史

| 版本 | 亮点 |
|------|------|
| **v1.0.7** | 支持作者（微信/支付宝收款码）、日志面板、Cursor 交替按键、测试无阈值限制、导入图片支持 step4 + 自由选步骤 |
| **v1.0.6** | (内部迭代) |
| **v1.0.5** | (内部迭代) |
| **v1.0.4** | (内部迭代) |
| **v1.0.3** | 优化 Cursor 追踪（速度 0.31 + 增加移动距离）、更新弹窗简约风格、更换 App 图标、修复编译错误 |
| **v1.0.2** | (内部迭代) |
| **v1.0.1** | 包名重构为 `com.yihuan.autofish`，更新界面文字和文档 |
| **v1.0.0** | 初始版本，支持基础自动钓鱼 |

---

## 🔧 构建方式

```bash
# 构建 Release APK（已配置签名密钥）
./gradlew assembleRelease

# 或使用项目中的批处理脚本
build.bat
```

APK 输出：`app/release/yihuan-autofish_{version}.apk`

> 签名密钥位于 `autofish-release.keystore`，密码见 `app/build.gradle.kts`。

---

## 📦 远程更新

项目集成了 [AppUpdate](https://github.com/azhon/AppUpdate) 库，启动时自动检查 GitHub Releases 更新。更新配置在 `update.json` 中：

```json
{
  "versionCode": 7,
  "versionName": "1.0.7",
  "apkUrl": "https://github.com/wly0629/yihuan-autofish-android/releases/download/v1.0.7/yihuan-autofish_1.0.7.apk",
  "apkSize": 25500000,
  "updateContent": "支持作者、日志面板、Cursor 交替按键、测试无阈值限制"
}
```

---

## ⚠️ 注意事项

- **横屏模式**：请确保游戏处于横屏状态
- **权限持久化**：无障碍服务和悬浮窗权限可能被系统清理，建议加入系统白名单
- **模板适配**：不同分辨率/设备可能需要重新截取模板图片，详见 `assets/scripts/README.md`
- **仅限学习交流**，请勿用于商业用途

---

## 🙏 支持作者

喜欢这个项目？请作者喝杯咖啡吧 ☕

| 微信 | 支付宝 |
|------|--------|
| ![微信收款码](app/src/main/assets/scripts/wechat_qr.png) | ![支付宝收款码](app/src/main/assets/scripts/alipay_qr.png) |

---

## 📄 开源许可

仅供学习交流使用，请勿用于商业用途。
