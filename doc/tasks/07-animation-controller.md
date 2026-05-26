# 任务 07 — AnimationController

> 依赖：T06（PetEngine）、T01（数据模型）  
> 阻塞：T08（AutonomousBehavior）、T09（PetOverlayService）

## 任务列表

- [ ] **T07-01 实现核心状态管理**
  - **描述**：按详细设计 §1.3.4，创建 `class AnimationController`，管理 `currentState`（StateFlow）、`currentFrame`（StateFlow）、`petSize`（StateFlow）
  - **输入**：T06（PetEngine）、T01-04（PetAnimationState）
  - **输出**：`engine/AnimationController.kt`（状态声明部分）
  - **验收**：编译通过，StateFlow 类型正确

- [ ] **T07-02 实现 load() / start() / stop() / reset() / switchState()**
  - **描述**：实现宠物加载、启动帧循环、停止、重置、状态切换等控制接口
  - **输入**：T07-01（状态声明）
  - **输出**：AnimationController 控制方法
  - **验收**：调用 load(petInfo) → petLoader 被创建；switchState(WAVING) → currentState 变为 WAVING

- [ ] **T07-03 实现 60FPS 帧循环**
  - **描述**：按详细设计 §1.3.4，用 `LaunchedEffect` + `withFrameMillis` 实现帧率控制循环，每秒 60 帧
  - **输入**：T07-02（start/stop）、T06-02（getCurrentFrame）
  - **输出**：`frameLoop()` 协程
  - **验收**：启动后 `currentFrame` 持续更新；stop 后停止更新

- [ ] **T07-04 实现一次性动画逻辑**
  - **描述**：按详细设计 §1.3.4，区分 `PLAY_ONCE`（waving/jumping/failed）和 `LOOP`（idle/running/waiting/review），PLAY_ONCE 结束后自动回 idle
  - **输入**：T07-03（帧循环）
  - **输出**：`getLoopMode()` + 帧循环中自动回 idle 逻辑
  - **验收**：switchState(WAVING) → 播完 waving 所有帧 → 自动切换到 IDLE

- [ ] **T07-05 实现 setScale()**
  - **描述**：`setScale(scale: Float)` → `petSize.value = Size(192f * scale, 208f * scale)`
  - **输入**：T07-01（petSize StateFlow）
  - **输出**：`setScale()` 方法
  - **验收**：setScale(1.5) → petSize.value = Size(288f, 312f)
