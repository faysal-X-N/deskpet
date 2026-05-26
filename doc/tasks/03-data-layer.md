# 任务 03 — 数据层（Data Layer）

> 依赖：T01（数据模型）、T02（基础层）  
> 阻塞：T06（PetEngine）、T09（PetOverlayService）、T10（UI 层）

## 任务列表

- [ ] **T03-01 创建 PetStore（DataStore 封装）**
  - **描述**：按详细设计 §1.4.2，创建 `class PetStore`，实现 `petListFlow`、`activePetIdFlow`、`updatePetList()`、`setActivePetId()`，使用 `preferencesDataStore`
  - **输入**：T01-02（PetInfo 序列化）、T00-02（DataStore 依赖）
  - **输出**：`data/PetStore.kt`
  - **验收**：写入一只 PetInfo → 重启 App → 通过 `petListFlow` 正确读取

- [ ] **T03-02 创建 PetRepository**
  - **描述**：按详细设计 §1.4.1，创建 `class PetRepository`，封装 `getAllPets()`、`addPet()`、`deletePet()`、`setActivePet()`、`savePosition()`、`isFirstLaunch()`
  - **输入**：T03-01（PetStore 实例）、T01-02（PetInfo 模型）
  - **输出**：`data/PetRepository.kt`
  - **验收**：调用 `addPet` → `getAllPets().first()` 包含新宠物；`setActivePet(id)` → `getActivePetId().first()` 正确

- [ ] **T03-03 创建 FileManager**
  - **描述**：按详细设计 §1.4.3，实现 `importCodexPet(uri)`、`importGif(uri)`、`deletePet(petInfo)`，含 ZIP 解压、GIF 魔数校验、10MB 限制
  - **输入**：T01-06（ImportException）、T02-02（ImageDecoder）
  - **输出**：`data/FileManager.kt`
  - **验收**：传入有效 Codex Pet ZIP → 返回 PetInfo；传入有效 GIF → 返回 PetInfo；传入无效文件 → 抛出 ImportException
