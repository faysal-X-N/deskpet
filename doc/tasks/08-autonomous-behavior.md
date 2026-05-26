# 任务 08 — AutonomousBehavior

> 依赖：T07（AnimationController）  
> 阻塞：T09（PetOverlayService）

## 任务列表

- [ ] **T08-01 实现自主行为调度器**
  - **描述**：按详细设计 §1.3.5，创建 `class AutonomousBehavior`，维护 `lastTouchTime`、`autoStates` 列表（RUNNING/JUMPING/WAITING/REVIEW）、协程 `loop()`
  - **输入**：T07（AnimationController）、T01-04（PetAnimationState）
  - **输出**：`engine/AutonomousBehavior.kt`
  - **验收**：编译通过

- [ ] **T08-02 实现随机间隔 + 触摸重置**
  - **描述**：`onUserTouch()` 重置 `lastTouchTime`；`loop()` 中 `delay(random 5~30s)` → 检查 30s 无触摸 → 随机选一个 `autoState` → `animationController.switchState()` → `delay(动画时长)` → AnimationController 自动回 idle
  - **输入**：T08-01
  - **输出**：完整自主行为逻辑
  - **验收**：启动 30 秒无触摸 → 宠物自动做动作 → 播完后回 idle；触摸后计时器重置

- [ ] **T08-03 仅在 Codex Pet 时启用**
  - **描述**：GIF 宠物不创建 AutonomousBehavior 实例；创建时检查 `petType`
  - **输入**：T09（PetOverlayService 调用方）
  - **输出**：条件创建逻辑
  - **验收**：Codex Pet → 有自主行为；GIF → 无自主行为，不报错
