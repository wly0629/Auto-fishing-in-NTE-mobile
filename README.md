# 异环手游自动钓鱼 🐟

基于 OpenCV 模板匹配 + Android AccessibilityService 的异环手游自动钓鱼脚本。

## 📋 功能概述

自动检测游戏中的钓鱼交互界面，模拟点击操作完成钓鱼全流程：

1. **甩竿** — 检测并点击「甩竿」按钮（step1）
2. **确认** — 检测并点击「refer」确认按钮
3. **拉杆** — 自动将光标（cursor）拉至目标区域（target）
4. **循环** — 支持左右两侧 target，自动循环钓鱼

## 🏗️ 项目结构

```
AutoFish/
├── app/src/main/java/com/yihuan/autofish/
│   ├── MainActivity.kt              ← 主界面 UI
│   ├── ClickScript.kt                ← 核心钓鱼脚本逻辑
│   ├── ImageMatcher.kt               ← OpenCV 模板匹配引擎
│   ├── FloatingOverlayService.kt     ← 悬浮窗控制服务
│   ├── ScreenCaptureActivity.kt      ← 录屏权限申请（透明 Activity）
│   ├── ScreenCaptureService.kt       ← 录屏前台服务（MediaProjection）
│   ├── AutoClickAccessibilityService.kt ← 无障碍点击服务
│   ├── ClickEventMonitor.kt          ← 点击事件监控
│   ├── ClickTestActivity.kt          ← 点击测试页面
│   ├── TestActivity.kt               ← 调试测试页面
│   ├── OpenCVHelper.kt               ← OpenCV 初始化辅助
│   └── ScriptStep.kt                 ← 脚本步骤数据类
├── app/src/main/assets/scripts/      ← 模板匹配图片
│   ├── step1.jpg                     ← 甩竿按钮
│   ├── step3refer.jpg                ← refer 确认按钮
│   ├── step3cursor.jpg               ← 光标指针
│   ├── step3target.jpg               ← 目标区域
│   ├── step3left.jpg                 ← 左拉按钮
│   └── step3right.jpg                ← 右拉按钮
├── app/build.gradle.kts              ← 构建配置
├── opencv/                           ← OpenCV SDK 模块
├── autofish-release.keystore         ← APK 签名密钥
└── README.md                         ← 本文件
```

## ⚙️ 核心参数

所有参数均在 `ClickScript.kt` 的 `companion object` 中定义。

### 匹配阈值

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `HIGH_THRESHOLD` | 0.60 | 高精度匹配（甩竿、按钮） |
| `LIGHT_THRESHOLD` | 0.60 | 轻量快速检测 |
| `CURSOR_THRESHOLD` | 0.95 | 光标检测（无背景，高值） |
| `TARGET_THRESHOLD` | 0.97 | 目标检测（无背景，极高值） |

### 超时时间

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `STEP1_POST_DELAY_MS` | 3000ms | 点甩竿后等待 refer 出现 |
| `CLICK_INTERVAL_MS` | 200ms | 循环点击间隔 |
| `FRAME_POLL_MS` | 100ms | 帧轮询间隔 |
| `TARGET_TRACKING_TIMEOUT_MS` | 60s | 目标跟踪超时 |
| `FIND_TIMEOUT_MS` | 180s | 查找超时（甩竿/refer） |

### 长按时间

| 操作 | 当前值 | 位置 |
|------|--------|------|
| LEFT/RIGHT 长按 | 200ms | `ClickScript.kt` → 搜索 `doLongClick` |

### 搜索区域

| 目标 | 区域 |
|------|------|
| step1（甩竿） | 屏幕右下角 (2/3W, 2/3H) → (W, H) |
| refer（确认） | 屏幕左上角 (0, 0) → (1/2W, 1/4H) |
| left 按钮 | 屏幕左下角 (0, 1/2H) → (1/3W, H) |
| right 按钮 | 屏幕右下角 (2/3W, 1/2H) → (W, H) |

## 🔧 构建方式

```bash
# 构建 Release APK（已配置签名）
./gradlew assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

## 📝 版本历史

- **v1.0.1** — 包名重构为 `com.yihuan.autofish`，更新界面文字和文档
- **v1.0.0** — 初始版本，支持异环手游自动钓鱼

## ⚖️ 开源许可

仅供学习交流使用，请勿用于商业用途。
