# 任务 10 — UI 层（UI Screens）

> 依赖：T03（数据层）、T09（悬浮窗层）、T01（数据模型）  
> 阻塞：无（最后一批任务）

## 任务列表

- [ ] **T10-01 创建 OnboardingGuide**
  - **描述**：按详细设计 §1.1.2，创建 `@Composable OnboardingGuide`，3 页引导（权限→操作→导入），首次启动时显示
  - **输入**：T03-02（isFirstLaunch）
  - **输出**：`ui/OnboardingGuide.kt`
  - **验收**：首次启动 → 显示引导 Dialog；点击开始使用 → 消失，标记 isFirstLaunch=false

- [ ] **T10-02 创建 PetListViewModel**
  - **描述**：按详细设计 §1.1.3，创建 `class PetListViewModel`，暴露 `petList: StateFlow`、`activePetId: StateFlow`、`selectPet(id)`、`deletePet(id)`
  - **输入**：T03-02（PetRepository）
  - **输出**：`ui/PetListViewModel.kt`
  - **验收**：`petList` 随 PetRepository 数据变化而更新

- [ ] **T10-03 创建 PetListScreen**
  - **描述**：按详细设计 §1.1.3，创建 `@Composable PetListScreen`，LazyColumn 展示宠物列表，高亮激活项，提供「开启/关闭悬浮窗」「导入」按钮
  - **输入**：T10-02（PetListViewModel）
  - **输出**：`ui/PetListScreen.kt`
  - **验收**：列表显示已导入宠物；点击「开启」→ Service 启动；点击「关闭」→ Service 停止

- [ ] **T10-04 创建 ImportScreen**
  - **描述**：按详细设计 §1.1.4，创建 `@Composable ImportScreen`，两个按钮分别启动 Codex Pet 和 GIF 的文件选择器，调用 FileManager.importCodexPet/Gif
  - **输入**：T03-03（FileManager）、T01-06（ImportException）
  - **输出**：`ui/ImportScreen.kt`
  - **验收**：选择有效 Codex Pet ZIP → 导入成功，列表刷新；选择无效文件 → Snackbar 错误提示

- [ ] **T10-05 创建 MainActivity**
  - **描述**：按详细设计 §1.1.1，创建 `class MainActivity`，整合 OnboardingGuide → PetListScreen 导航；管理 `startPetOverlay()` / `stopPetOverlay()`；首次启动判断
  - **输入**：T10-01（OnboardingGuide）、T10-03（PetListScreen）、T10-04（ImportScreen）、T03-02（PetRepository）、T09-03（PetOverlayService）
  - **输出**：`MainActivity.kt`
  - **验收**：
    - 首次启动 → 显示引导 → 关闭后进入宠物列表 ✅
    - 非首次 → 直接进入宠物列表 ✅
    - 点击「开启」→ 悬浮窗出现 ✅
    - 点击「关闭」→ 悬浮窗消失 ✅
    - 导入宠物 → 列表更新 ✅
    - 切换宠物 → 悬浮窗更新 ✅

- [ ] **T10-06 端到端集成测试**
  - **描述**：完整流程测试：安装 → 首次引导 → 导入 Codex Pet → 开启悬浮窗 → 点击/拖拽/缩放 → 导入 GIF → 切换 → 关闭 App → 重新打开（位置记忆）
  - **输入**：所有已完成模块
  - **输出**：测试通过确认
  - **验收**：全部 AC-01~AC-10 通过（与 proposal.md 验收标准一致）
