# 任务 06 — PetEngine

> 依赖：T04（SpritesheetParser）、T05（GifRenderer）、T01（数据模型）、T03（数据层）  
> 阻塞：T07（AnimationController）、T09（PetOverlayService）

## 任务列表

- [ ] **T06-01 实现 PetLoader 密封类 + createLoader()**
  - **描述**：按详细设计 §1.3.1，创建 `sealed class PetLoader`（CodexPet / Gif），实现 `createLoader(petInfo)` → 根据 `petInfo.type` 分发创建对应 Loader
  - **输入**：T01-02（PetInfo）、T01-03（CodexPetConfig）、T04（SpritesheetParser.parse）
  - **输出**：`PetEngine.PetLoader` + `createLoader()` 方法
  - **验收**：传入 type=CODEX_PET → 返回 PetLoader.CodexPet；type=GIF → PetLoader.Gif

- [ ] **T06-02 实现 getCurrentFrame()**
  - **描述**：`getCurrentFrame(loader, state, frameIndex)` → CodexPet → SpritesheetParser.extractFrame；GIF → GifRenderer.getFrame
  - **输入**：T04-03（extractFrame）、T05-03（getFrame）
  - **输出**：`PetEngine.getCurrentFrame()` 方法
  - **验收**：CodexPet loader → 返回正确的 spritesheet 帧；GIF loader → 返回正确的 GIF 帧

- [ ] **T06-03 实现 getFrameCount()**
  - **描述**：获取当前 loader+state 的总帧数（CodexPet→SpritesheetParser，GIF→frames.size）
  - **输入**：T04-05（getFrameCount）、T05-02（frames 列表）
  - **输出**：`PetEngine.getFrameCount()` 方法
  - **验收**：各类型返回正确的帧数
