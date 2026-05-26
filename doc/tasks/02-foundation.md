# 任务 02 — 基础层（Foundation）

> 依赖：T00（项目搭建）  
> 阻塞：T03（PetRepository）、T04（SpritesheetParser）、T09（PetOverlayService）  
> 可与 T01 并行

## 任务列表

- [ ] **T02-01 创建 PermissionManager**
  - **描述**：按详细设计 §1.5.1，创建 `object PermissionManager`，包含 `canDrawOverlays()` 和 `openOverlaySettings()`
  - **输入**：无
  - **输出**：`foundation/PermissionManager.kt`
  - **验收**：编译通过；在模拟器上调用 `canDrawOverlays()` 返回正确值

- [ ] **T02-02 创建 ImageDecoder**
  - **描述**：按详细设计 §1.5.2，创建 `class ImageDecoder`，包含 `decodeBitmapRegion()`、`decodeFullBitmap()`、`getImageDimensions()`
  - **输入**：无（Android SDK 内置 API）
  - **输出**：`foundation/ImageDecoder.kt`
  - **验收**：传入一张测试 spritesheet → `decodeBitmapRegion(rect)` 返回正确区域的 Bitmap；`getImageDimensions` 返回正确宽高
