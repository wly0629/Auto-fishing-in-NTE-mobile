# 异环手游自动钓鱼 🐟

基于 OpenCV 模板匹配 + Android AccessibilityService 的异环手游自动钓鱼脚本。

## 📋 功能概述

自动检测游戏中的钓鱼交互界面，模拟点击操作完成钓鱼全流程：
1. 检测「甩竿」按钮（step1）
2. 检测「refer」确认按钮
3. 自动将光标（cursor）拉至目标区域（target）
4. 支持左右两侧 target

## 🏗️ 项目结构

```
AutoFish/
├── app/src/main/java/com/yihuan/autofish/
│   ├── MainActivity.kt              ← 主界面 UI
│   ├── ClickScript.kt                ← 核心钓鱼脚本逻辑
│   ├── ImageMatcher.kt               ← OpenCV 模板匹配引擎
│   ├── FloatingOverlayService.kt     ← 悬浮窗控制服务
│   ├── ScreenCaptureActivity.kt      ← 录屏权限申请（透明 Activity）
│   ├── ScreenCaptureService.kt       ← 录屏前台服务
│   ├── AutoClickAccessibilityService.kt ← 无障碍点击服务
│   ├── ClickEventMonitor.kt          ← 点击事件监控
│   ├── ClickTestActivity.kt          ← 点击测试页
│   ├── TestActivity.kt               ← 调试测试页
│   ├── OpenCVHelper.kt               ← OpenCV 初始化辅助
│   └── ScriptStep.kt                 ← 脚本步骤数据类
├── app/src/main/assets/scripts/      ← 模板图片
│   ├── step1.jpg, step3refer.jpg, step3cursor.jpg
│   ├── step3target.jpg, step3left.jpg, step3right.jpg
├── app/build.gradle.kts              ← 构建配置
├── opencv/                           ← OpenCV SDK 模块
├── autofish-release.keystore         ← 签名密钥
└── README.md                         ← 本文件
```

## ⚙️ 核心参数

| 参数 | 默认值 | 作用 |
|------|--------|------|
| HIGH_THRESHOLD | 0.60 | 高精度匹配阈值 |
| LIGHT_THRESHOLD | 0.60 | 轻量检测阈值 |
| CURSOR_THRESHOLD | 0.95 | 光标检测阈值 |
| TARGET_THRESHOLD | 0.97 | 目标检测阈值 |
| CLICK_INTERVAL_MS | 200ms | 循环点击间隔 |
| FIND_TIMEOUT_MS | 180s | 查找超时时间 |
| TARGET_TRACKING_TIMEOUT_MS | 60s | 目标跟踪超时 |

所有参数均在 `ClickScript.kt` 的 `companion object` 中定义。

## 🔧 构建方式

```bash
# 使用 Gradle 构建 Release APK
./gradlew assembleRelease
```

APK 输出路径：`app/build/outputs/apk/release/app-release.apk`

## 📝 版本历史

- **1.0.1** — 包名重构为 com.yihuan.autofish，优化参数配置
- **1.0.0** — 初始版本，支持异环手游自动钓鱼
