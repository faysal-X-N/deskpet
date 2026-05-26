# 🐾 DeskPet — 桌面宠物

> 在手机上养一只会互动的小宠物：浮在任何 App 上方，点击招手，拖拽移动，偶尔还会自己走走跳跳。

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-blue)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple)](https://kotlinlang.org)

---

## ⚠️ 免责声明

**本应用由 AI 辅助生成，按"原样"提供，不提供任何形式的担保。**

- 本应用不含任何联网权限，不收集任何用户数据，纯本地运行
- 作者不对因使用本应用导致的设备损坏、数据丢失、电池消耗、或任何其他损失承担责任
- 悬浮窗权限（`SYSTEM_ALERT_WINDOW`）为 Android 系统级权限，部分国产 ROM（MIUI、ColorOS 等）可能在后台自动回收此权限，导致宠物消失——这属于系统行为，非本应用 bug
- 请勿用于商业用途的二次分发，除非你清楚自己做的事情
- 使用本应用即表示你同意上述条款

---

## 📱 使用教程

### 安装

1. 下载最新 APK 文件（见 [Releases](../../releases)）
2. 在手机上打开 APK，允许"未知来源"安装（如需要）
3. 安装完成后打开「桌面宠物」

### 第一次使用

首次打开 App 会弹出引导对话框，说明基本操作和权限需求：

- **悬浮窗权限**：点击「知道了」后，如果需要，系统会引导你开启悬浮窗权限。**必须开启此权限，宠物才能显示在屏幕上。**

### 开启宠物

1. 在宠物栏中选择一只宠物
2. 点击「**开启**」按钮
3. 宠物会以悬浮窗形式出现在屏幕上方

### 操作方式

| 操作 | 效果 |
|------|------|
| **点击宠物** | Codex Pet：播放招手动画；GIF：弹跳反馈 |
| **长按 + 拖拽** | 移动宠物位置（Codex Pet 拖拽方向触发对应方向的动画） |
| **双指缩放** | 调整宠物大小 |

### 关闭宠物

- 回到 App 点击「**关闭**」按钮
- 或在多任务界面划掉 App（宠物会随之消失）

### 导入新宠物

支持两种格式：

#### Codex Pet 素材包（推荐）

1. 下载 `.zip` 格式的 Codex Pet 素材包（需包含 `pet.json` + `spritesheet.webp`/`.png`）
2. 在 App 中点击「**导入**」→「**🎨 导入 Codex Pet 素材包**」
3. 选择下载的 zip 文件，宠物会自动出现在宠物栏中

> Codex Pet 社区有 2500+ 宠物可供下载，支持 9 种动画状态：idle、running、jumping、waving 等。

#### GIF 动图

1. 准备一个 `.gif` 动图文件
2. 在 App 中点击「**导入**」→「**📷 导入 GIF 动图**」
3. 选择 GIF 文件即可

> GIF 宠物仅循环播放动图内容，不区分动画状态。点击时仅做物理弹跳反馈。

### 调整大小

在宠物栏中点击缩放选项（50%、100%、150%、200%、250%、300%），或直接在悬浮窗宠物上**双指捏合/扩张**。

### 删除宠物

在宠物栏中**长按**宠物卡片，选择删除。

### 备份与恢复

- **备份全部**：点击右上角「⋮」→「💾 备份全部」，可将所有宠物数据打包导出
- **恢复备份**：点击「导入」→「💾 恢复备份」，选择之前导出的 `.zip` 备份文件

### 切换宠物

在宠物栏中点击不同宠物即可切换。每只宠物的位置和大小是独立记忆的。

---

## ✨ 功能特性

- 🪟 **悬浮窗显示** — 宠物浮在所有 App 之上
- 👆 **触摸交互** — 点击招手、长按拖拽移动、双指缩放
- 🎬 **动画系统** — Codex Pet 支持 9 种标准动画状态
- 🤖 **自主行为** — 30 秒无操作后宠物会自己散步、蹦跳、东张西望
- 📦 **Codex Pet 兼容** — 支持社区 2500+ 宠物素材包
- 🖼️ **GIF 支持** — 直接导入 GIF 动图即可作为宠物
- 💾 **备份恢复** — 一键导出/恢复全部宠物数据
- 🔒 **隐私优先** — 无网络权限，纯本地运行，不收集任何数据

---

## 🛠 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 开发语言 |
| Jetpack Compose | UI 框架 |
| Compose Canvas | 悬浮窗宠物渲染 |
| WindowManager | 悬浮窗管理 |
| DataStore Preferences | 本地持久化存储 |
| BitmapRegionDecoder | 精灵表解码 |
| Android Movie | GIF 解码 |

**最低要求**：Android 8.0（API 26）

---

## 🏗 构建

```bash
# 克隆仓库
git clone https://github.com/faysal-X-N/deskpet.git
cd deskpet

# 生成 Gradle wrapper（如缺失）
gradle wrapper

# 构建 APK
./gradlew :app:assembleDebug

# 运行单元测试
./gradlew :app:testDebugUnitTest
```

用 Android Studio 打开项目目录即可直接运行。

---

## 📂 项目结构

```
app/src/main/java/com/deskpet/
├── MainActivity.kt              # 主界面入口
├── DeskPetApp.kt                # Application
├── ui/
│   ├── PetListScreen.kt         # 宠物栏
│   ├── ImportScreen.kt          # 导入界面
│   └── theme/                   # Compose 主题
├── overlay/
│   ├── PetOverlayService.kt     # 悬浮窗 Service
│   ├── PetOverlayRenderer.kt    # Canvas 渲染
│   └── GestureHandler.kt        # 手势处理
├── engine/
│   ├── PetEngine.kt             # 引擎入口
│   ├── SpritesheetParser.kt     # 精灵表解析
│   ├── GifRenderer.kt           # GIF 渲染
│   ├── AnimationController.kt   # 动画控制
│   └── AutonomousBehavior.kt    # 自主行为
├── data/
│   ├── PetRepository.kt         # 数据仓库
│   ├── PetStore.kt              # DataStore
│   ├── FileManager.kt           # 文件管理
│   └── model/                   # 数据模型
└── foundation/
    └── PermissionManager.kt     # 权限管理
```

---

## 📋 已知限制

- 国产 ROM（MIUI/ColorOS 等）可能在后台自动关闭悬浮窗，需将应用加入白名单
- 切换宠物后需手动关闭再开启悬浮窗才能生效
- 不支持多只宠物同屏（计划中）

---

## 📄 许可证

MIT License — 详见 [LICENSE](LICENSE)
