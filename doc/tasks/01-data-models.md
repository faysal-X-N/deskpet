# 任务 01 — 数据模型

> 依赖：T00（项目搭建）  
> 阻塞：T03（数据层）、T04-T08（引擎层）、T09（悬浮窗层）、T10（UI 层）

## 任务列表

- [ ] **T01-01 创建 PetType 枚举**
  - **描述**：创建 `enum class PetType { CODEX_PET, GIF }`，放在 `data/model/`
  - **输入**：无
  - **输出**：`data/model/PetType.kt`
  - **验收**：编译通过

- [ ] **T01-02 创建 PetInfo 数据类**
  - **描述**：按详细设计 §5.1，创建 `@Serializable data class PetInfo`，包含 id、type、displayName、petJsonPath、spritesheetPath、positionX、positionY、scale、isActive
  - **输入**：T01-01（PetType 引用）
  - **输出**：`data/model/PetInfo.kt`
  - **验收**：编译通过，JSON 序列化/反序列化测试通过

- [ ] **T01-03 创建 CodexPetConfig + AnimationState 数据类**
  - **描述**：按详细设计 §5.2，创建 `CodexPetConfig`（id/displayName/description/spritesheetPath/schema_version/states）和 `AnimationState`（name/row/frames）
  - **输入**：无
  - **输出**：`data/model/CodexPetConfig.kt`
  - **验收**：编译通过，JSON 反序列化 pet.json 示例通过

- [ ] **T01-04 创建 PetAnimationState 枚举**
  - **描述**：按详细设计 §5.3，创建 9 状态枚举（IDLE→REVIEW），含 `fromSpecName()` 方法
  - **输入**：T01-03（AnimationState 引用关系）
  - **输出**：`data/model/PetAnimationState.kt`
  - **验收**：`fromSpecName("running-right")` 返回 `RUNNING_RIGHT`

- [ ] **T01-05 创建 DragDirection 枚举**
  - **描述**：创建 `enum class DragDirection { LEFT, RIGHT, UP, DOWN }`，供 GestureHandler 使用
  - **输入**：无
  - **输出**：`data/model/DragDirection.kt`
  - **验收**：编译通过

- [ ] **T01-06 创建 ImportException 密封类**
  - **描述**：按详细设计 §3.1，创建 `sealed class ImportException`（FileNotFound、InvalidFormat、FileTooLarge、ExtractionFailed）
  - **输入**：无
  - **输出**：`data/model/ImportException.kt`
  - **验收**：编译通过，所有子类可实例化
