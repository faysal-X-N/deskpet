# 任务 04 — SpritesheetParser

> 依赖：T01（数据模型）、T02（ImageDecoder）  
> 阻塞：T06（PetEngine）

## 任务列表

- [ ] **T04-01 实现 pet.json 解析**
  - **描述**：读取 pet.json 文件 → `kotlinx.serialization` 反序列化为 `CodexPetConfig`；若 `states` 为 null → 使用默认 9 行映射（硬编码 `DEFAULT_STATE_MAP`）
  - **输入**：T01-03（CodexPetConfig）、T00-02（kotlinx-serialization 依赖）
  - **输出**：`SpritesheetParser.parse()` 方法
  - **验收**：传入有效 pet.json → 返回非 null 的 CodexPetConfig；传入格式错误的 pet.json → 捕获 JsonDecodingException

- [ ] **T04-02 实现 Cell 坐标计算**
  - **描述**：按详细设计 §1.3.2，实现 `calculateCellRect(row, col, spritesheetW, spritesheetH)` → `Rect`；spritesheetW/8=cellW，spritesheetH/9=cellH
  - **输入**：T02-02（ImageDecoder.getImageDimensions）
  - **输出**：`SpritesheetParser.calculateCellRect()` 方法
  - **验收**：传入 1536×1872 → row=0, col=0 → Rect(0,0,192,208)；row=8, col=7 → Rect(1344,1664,1536,1872)

- [ ] **T04-03 实现帧提取 extractFrame()**
  - **描述**：按详细设计 §1.3.2，`extractFrame(config, state, frameIndex)` → 用 `BitmapRegionDecoder.decodeRegion(cellRect)` 提取该帧 Bitmap
  - **输入**：T04-02（坐标计算）、T02-02（ImageDecoder.decodeBitmapRegion）
  - **输出**：`SpritesheetParser.extractFrame()` 方法
  - **验收**：传入 Codie 标准 spritesheet → extractFrame(IDLE, 0) 返回非空 Bitmap；extractFrame(IDLE, 超出帧数) → 返回 null

- [ ] **T04-04 实现非空帧检测**
  - **描述**：`isNonEmptyFrame(bitmap)` → 采样检查 5 个像素（四角+中心）alpha 值，任一 >0 即为非空
  - **输入**：T04-03（提取的帧 Bitmap）
  - **输出**：`SpritesheetParser.isNonEmptyFrame()` 方法
  - **验收**：传入纯透明帧 → false；传入有内容帧 → true

- [ ] **T04-05 实现 getFrameCount()**
  - **描述**：若有 `config.states` → 返回对应 `state.frames`；若无 → 扫描该行，从 col=0 起统计连续非空帧数
  - **输入**：T04-03、T04-04
  - **输出**：`SpritesheetParser.getFrameCount()` 方法
  - **验收**：Codie 的 idle 行 → 返回 6
