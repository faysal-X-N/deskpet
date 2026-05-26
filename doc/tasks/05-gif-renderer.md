# 任务 05 — GifRenderer

> 依赖：T01（数据模型）  
> 阻塞：T06（PetEngine）  
> 可与 T04 并行

## 任务列表

- [ ] **T05-01 配置 Coil ImageLoader**
  - **描述**：在 DeskPetApp 中配置 Coil `ImageLoader`，注册 `GifDecoder.Factory()`（API26+）和 `AnimatedImageDecoder.Factory()`（API28+）
  - **输入**：T00-04（DeskPetApp）、T00-02（Coil 依赖）
  - **输出**：`DeskPetApp.kt` 中 Coil 初始化代码
  - **验收**：App 启动时 Coil 初始化无异常

- [ ] **T05-02 实现 GIF 帧解码**
  - **描述**：按详细设计 §1.3.3，`decodeFrames(filePath)` → 用 Coil 解码 GIF 所有帧，返回 `List<GifFrame>`（含 `Bitmap` + `delayMs`）
  - **输入**：T05-01（Coil 配置）
  - **输出**：`GifRenderer.decodeFrames()` 方法
  - **验收**：传入测试 GIF → 返回帧列表，帧数 >0，每帧 Bitmap 非空

- [ ] **T05-03 实现 getFrame()**
  - **描述**：`getFrame(loader, frameIndex)` → `frames[frameIndex % frames.size].bitmap`
  - **输入**：T05-02
  - **输出**：`GifRenderer.getFrame()` 方法
  - **验收**：传入 frameIndex=0 → 返回首帧；frameIndex=总帧数 → 循环回首帧
