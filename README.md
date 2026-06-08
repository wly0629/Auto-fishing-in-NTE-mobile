# AutoFish 🐟 - 异环自动钓鱼

基于 OpenCV 模板匹配 + Android AccessibilityService 的自动钓鱼脚本。

---

## 📁 文件结构

```
AutoFish/
├── app/src/main/java/com/smallrong/autoclicker/
│   ├── ClickScript.kt          ← 📌 核心脚本（所有参数都在这里）
│   ├── ImageMatcher.kt         ← OpenCV 模板匹配引擎
│   ├── MainActivity.kt         ← 主页 UI
│   ├── FloatingOverlayService.kt ← 悬浮窗服务
│   ├── ScreenCaptureActivity.kt  ← 录屏权限透明 Activity
│   ├── ScreenCaptureService.kt   ← 录屏服务
│   ├── AutoClickAccessibilityService.kt ← 无障碍点击服务
│   └── ...
├── app/build.gradle.kts        ← 版本号、BuildConfig 配置
└── README.md                   ← 本文件
```

---

## ⚙️ 参数速查

**所有参数在 `ClickScript.kt` 的 `companion object` 中**，搜索关键词即可找到。

### 匹配阈值

| 参数 | 关键词 | 当前值 | 作用 |
|------|--------|--------|------|
| step1 / refer / left/right 按钮 | `HIGH_THRESHOLD` | **0.60** | 精确查找时使用的阈值（step1、refer、按钮） |
| step1 / refer 可见性快速检测 | `LIGHT_THRESHOLD` | **0.60** | 轻量快速检测时使用的阈值 |
| cursor 指针 | `CURSOR_THRESHOLD` | **0.95** | 检测 cursor 指针位置的阈值（无背景，高值） |
| target 目标 | `TARGET_THRESHOLD` | **0.97** | 检测左右 target 的阈值（无背景，极高值） |

> **调整原则：** 值越高 → 匹配越严格（不容易误识别，但也可能漏掉）
> 值越低 → 匹配越宽松（更容易识别到，但也可能认错）

### 超时时间

| 参数 | 关键词 | 当前值 | 作用 |
|------|--------|--------|------|
| 点击 step1 后等待 refer | `STEP1_POST_DELAY_MS` | **3000** ms | 点完 step1 后等多久才循环检测 refer |
| 循环点击间隔 | `CLICK_INTERVAL_MS` | **200** ms | 循环点击 step1/refer 的间隔 |
| 帧轮询间隔 | `FRAME_POLL_MS` | **100** ms | 等待帧时的轮询间隔 |
| target 跟踪总超时 | `TARGET_TRACKING_TIMEOUT_MS` | **60_000** ms (60秒) | 跟踪阶段最多跑多久，超时强制退出 |
| step1 / refer 查找超时 | `FIND_TIMEOUT_MS` | **180_000** ms (3分钟) | 第一次查找 step1 或 refer 的超时时间 |

### 长按时间

**不在 `companion object` 里**，在 `runMainScript()` 的 Phase ④ 中。

| 操作 | 代码位置（搜索关键词） | 当前值 | 作用 |
|------|----------------------|--------|------|
| LEFT 长按 | `doLongClick(it.x, it.y, 200L)` | **200** ms | 长按左侧按钮把光标拉到左侧 target |
| RIGHT 长按 | `doLongClick(it.x, it.y, 200L)` | **200** ms | 长按右侧按钮把光标拉到右侧 target |

> 搜索 `200L` 或 `doLongClick` 找到这两处。

### 双 target 误匹配检测

| 参数 | 位置 | 当前值 | 作用 |
|------|------|--------|------|
| 左右 target 同时存在判定 | Phase ④ 跟踪循环中 | **3000** ms (3秒) | 左右 target 同时存在超过此时间 → 判定为误匹配，重置搜索 |

> 搜索 `bothTargetsPresentSince` 找到这段逻辑。

### 搜索区域

| 目标 | 关键词 | 区域计算 | 说明 |
|------|--------|---------|------|
| step1 | `waitForStep1` | 屏幕右下角 `(2/3W, 2/3H)` 到 `(W, H)` | 右下角区域 |
| refer | `checkReferLight` / `waitForRefer` | 屏幕左上角 `(0, 0)` 到 `(1/2W, 1/4H)` | 左上角窄条 |
| left 按钮 | `cacheLeftRightButtons` | 屏幕左下角 `(0, 1/2H)` 到 `(1/3W, H)` | 左半边下半区 |
| right 按钮 | `cacheLeftRightButtons` | 屏幕右下角 `(2/3W, 1/2H)` 到 `(W, H)` | 右半边下半区 |
| cursor | `findCursorInFrame` | 基于 refer 中心实时计算 | 动态变化，不正天改 |

### Phase ⑤ 超时重试

| 参数 | 代码位置 | 当前值 | 作用 |
|------|---------|--------|------|
| 全屏搜索间隔 | `referClicks % 15 == 0` | **15** 次 | 每 15 次局部搜索失败，切换全屏搜索 |
| 重置阈值 | `referClicks >= 40` | **40** 次 | 40 次点击 refer 后 step1 仍不出来 → 重置所有缓存重新开始 |

> 搜索 `referClicks` 找到这两处。

---

## 🧩 模板图片位置

模板图片存在 `app/src/main/assets/scripts/` 目录：

```
scripts/
├── step1.jpg          ← step1 按钮截图
├── step3refer.jpg     ← refer（触发后界面）截图
├── step3cursor.jpg    ← cursor 指针截图
├── step3target.jpg    ← target 目标截图
├── step3left.jpg      ← left 按钮截图
└── step3right.jpg     ← right 按钮截图
```

---

## 🔧 快速修改指引

### 想调高/低匹配精度
→ `ClickScript.kt` → 搜索 `THRESHOLD` → 调 4 个阈值

### 想加快/减慢点击速度
→ `ClickScript.kt` → 搜索 `CLICK_INTERVAL_MS` → 调 200ms
→ `ClickScript.kt` → 搜索 `STEP1_POST_DELAY_MS` → 调 3000ms

### 想改变长按力度
→ `ClickScript.kt` → 搜索 `doLongClick` → 修改最后一个数字（`200L`）

### 想改版本号
→ `app/build.gradle.kts` → 搜索 `versionCode` / `versionName`

---

## 📝 构建与发布

1. 改完后记得 **versionCode +1**（在 `app/build.gradle.kts`）
2. Android Studio → **Build → Clean Project → Rebuild Project**
3. 安装到手机，主页底部会显示当前版本号

---

*最后更新: 2026-06-07*
