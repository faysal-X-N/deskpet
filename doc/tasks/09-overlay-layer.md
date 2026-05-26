# 任务 09 — 悬浮窗层（Overlay Layer）

> 依赖：T01（模型）、T06（PetEngine）、T07（AnimationController）、T08（AutonomousBehavior）、T02（PermissionManager）、T03（数据层）  
> 阻塞：T10（UI 层）

## 任务列表

- [ ] **T09-01 创建 GestureHandler**
  - **描述**：按详细设计 §1.2.3，创建 `class GestureHandler`，实现 3 个 `pointerInput` 修饰符（tap/drag/transform），区分 Codex Pet（方向动画映射）和 GIF（仅移动+弹跳）
  - **输入**：T01-04（PetAnimationState）、T01-05（DragDirection）
  - **输出**：`overlay/GestureHandler.kt`
  - **验收**：Codex Pet 左拖 → 回调 onDrag(dx,dy,LEFT)；GIF 点击 → 回调 onTap（不触发 onDrag）

- [ ] **T09-02 创建 PetOverlayRenderer**
  - **描述**：按详细设计 §1.2.2，创建 `@Composable PetOverlayRenderer`，用 `Canvas` + `drawImage` 渲染 `animationController.currentFrame`
  - **输入**：T07（AnimationController）
  - **输出**：`overlay/PetOverlayRenderer.kt`
  - **验收**：`currentFrame` 变化 → Canvas 自动重绘；尺寸随 `petSize` 变化

- [ ] **T09-03 创建 PetOverlayService**
  - **描述**：按详细设计 §1.2.1，创建 `class PetOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner`，实现完整生命周期（onCreate/onStartCommand/onDestroy/onTaskRemoved）、addOverlayView()、removeOverlayView()、switchPet()、saveCurrentPosition()
  - **输入**：T09-01（GestureHandler）、T09-02（PetOverlayRenderer）、T06（PetEngine）、T07（AnimationController）、T08（AutonomousBehavior）、T03-02（PetRepository）、T02-01（PermissionManager）
  - **输出**：`overlay/PetOverlayService.kt`
  - **验收**：
    - startService → 悬浮窗出现在屏幕上 ✅
    - 拖拽 → 宠物跟随手指移动 ✅
    - 点击 Codex Pet → waving 动画 ✅
    - 点击 GIF → 弹跳效果 ✅
    - onTaskRemoved → 悬浮窗消失，位置已保存 ✅

- [ ] **T09-04 实现物理弹跳效果（GIF 点击）**
  - **描述**：按详细设计 §1.2.3，GIF 被点击时播放 20px 上弹 + 150ms 弹性回弹动画
  - **输入**：T09-03（WindowManager.layoutParams）
  - **输出**：GestureHandler 中 `playBounceEffect()` 协程
  - **验收**：点击 GIF → 宠物整体先上移 20px 再回弹，GIF 继续循环
