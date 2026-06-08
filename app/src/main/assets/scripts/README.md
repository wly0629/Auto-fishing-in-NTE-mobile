# 📂 assets/scripts/ — 模板图片存放目录

把所有需要在脚本中匹配的模板图片放在这个目录下。

## 📋 目录结构

```
assets/
└── scripts/
    ├── README.md
    ├── step1_login_btn.png      ← 示例: 登录按钮截图
    ├── step2_confirm_btn.png    ← 示例: 确认按钮截图
    └── step3_close_btn.png      ← 示例: 关闭按钮截图
```

## 🖼️ 如何截取模板图片

1. 在你需要自动操作的目标 App 中截图
2. 用图片编辑工具裁剪出你要匹配的目标区域（按钮、图标等）
3. 裁剪区域最好是唯一的视觉元素，避免与其他界面元素混淆
4. 保存为 PNG 格式，放入本目录

## ✂️ 裁剪建议

- **尺寸**: 50×50 到 200×200 像素之间效果最佳
- **唯一性**: 选择页面上独一无二的元素，避免多匹配
- **稳定性**: 选择不受滑动、动画等影响的固定元素
- **清晰度**: 尽量用高质量截图

## 🔧 ScriptStep 参数说明

```kotlin
ScriptStep(
    templateFileName = "xxx.png",   // 模板文件名（相对于 assets/scripts/）
    clickOffsetX = 0,               // 点击 X 偏移（相对匹配中心，正=右，负=左）
    clickOffsetY = 0,               // 点击 Y 偏移（相对匹配中心，正=下，负=上）
    matchThreshold = 0.8,           // 匹配阈值 0.0~1.0，值越高要求越严格
    timeoutMs = 10_000L,            // 超时毫秒，0=无限等待
    description = "描述文字"         // 用于日志显示的描述
)
```

## 🎯 偏移量使用场景

`clickOffsetX` 和 `clickOffsetY` 很有用！因为模板匹配返回的是**模板图片中心点**，而你要点击的位置可能和中心有偏差：

- 模板是按钮图标，但你要点击按钮上的文字区域 → 设置偏移
- 匹配标题栏返回整个标题区域中心，但你要点击右侧的 × 按钮 → 设置偏移
- 匹配某个大图标，要点击它旁边的文字 → 设置偏移

### 示例

```kotlin
// 模板是按钮图片 (100×40)，要点击按钮中心 → 偏移=0,0（默认）
ScriptStep("btn.png", 0, 0)

// 模板是标题栏 (300×60)，要点击右上角的 × (距中心右130, 上10)
ScriptStep("title_bar.png", 130, -10, description="关闭按钮")

// 匹配好友头像，点击它下面的名字 (下60像素)
ScriptStep("avatar.png", 0, 60, description="点击好友名字")
```

## 📝 然后在代码中定义脚本

在 `MainActivity.kt` 的 `loadDemoScript()` 方法中修改步骤：

```kotlin
private fun loadDemoScript() {
    val steps = listOf(
        ScriptStep(
            templateFileName = "your_step1.png",
            clickOffsetX = 0,
            clickOffsetY = 0,
            description = "你的第一步描述"
        ),
        ScriptStep(
            templateFileName = "your_step2.png",
            clickOffsetX = 10,
            clickOffsetY = -5,
            description = "你的第二步描述"
        ),
        // ... 更多步骤
    )
    clickScript.loadScript(steps)
}
```
