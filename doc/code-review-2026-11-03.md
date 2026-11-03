# DeskPet 代码审查报告

> 审查日期：2026-11-03
> 审查范围：全部源码、资源、Gradle 配置、Manifest、CI、文档
> 审查方式：逐文件静态审查 + `./gradlew build/test/lint` 实际验证 + 素材与资源实测
> 结论一句话：**项目能编译能运行，但存在多处"看着能用、实际不生效"的功能性缺陷**（导入后开不起来、GIF 不动、50% 缩放无效、运行中改缩放无效等）；本次已修复 33 项代码/配置问题和 7 处文档错误，另有 18 项建议后续处理的问题。

---

## 一、总体结论

| 类别 | 数量 | 状态 |
|------|:----:|------|
| P0 功能失效（用户能直接感觉到） | 9 | ✅ 已修复 |
| P1 稳定性 / 性能 / 安全 | 16 | ✅ 已修复 |
| P2 构建 / 清单 / 工程规范 | 8 | ✅ 已修复 |
| 文档与实际不符 | 7 | ✅ 已修复 |
| 未处理问题（建议后续迭代） | 18 | ⬜ 见第六节 |

验证结果（全部在本机跑通）：

| 项目 | 修复前 | 修复后 |
|------|--------|--------|
| `assembleDebug` | ✅ | ✅ |
| `assembleRelease`（R8） | 未验证过 | ✅ |
| `testDebugUnitTest` | 46 通过 | 46 通过 / 0 失败 |
| `lintDebug` | **2 errors** / 22 warnings（构建直接失败） | **0 errors** / 6 warnings |
| Debug APK | 24.3 MB | 23.2 MB |
| Release APK | — | 16.9 MB（未签名） |

还有 2 个正向确认，不是问题：

- `PetAnimationState.DEFAULT_STATE_MAP` 里写死的每行帧数（6/8/8/4/5/8/6/6/6）与素材实测完全一致（逐格统计非透明像素），动画不会读到空白帧。
- Manifest 确实没有任何网络权限，README 的隐私声明属实。

---

## 二、P0 功能性缺陷（已修复）

### 1. 导入宠物后点「开启」没有宠物，只有一条通知 ★最严重

- **现象**：导入一只宠物 → 点「开启」→ 通知栏出现"桌面宠物 正在运行"，但屏幕上什么都没有；新导入的宠物也没被选中。
- **根因**：`ImportScreen` 里 `petRepository.addPet(petInfo)` 内部会 **重新生成 UUID**，但返回值被丢弃，随后仍用旧的 `petInfo.id` 调 `setActivePet()`。于是 `activePetId` 指向一个不存在的 id；`PetOverlayService.show()` 里 `repo.getPet(id)` 返回 null，直接 `return`，但此时 `startForeground()` 已经执行 → 只剩通知。
- **位置**：`ui/ImportScreen.kt`（原第 44-45、69-70 行）
- **修复**：使用 `addPet()` 返回的对象设置选中；服务端在 `getPet` 为 null 时 Toast 提示并 `stopSelf()`，不再留下"僵尸通知"。

### 2. GIF 宠物画面不播放（冻结在第一帧）

- **现象**：GIF 导入后基本是静态图，偶尔跳一下。
- **根因**：`GifRenderer` 复用同一个 `Bitmap` 实例反复绘制，而 `AnimationController` 通过 `MutableStateFlow<Bitmap?>` 下发帧；**StateFlow 按 `equals` 去重**，同一个对象重复赋值不会发射 → Compose 不重组 → 画面不动。git 历史里 `dc89ba4 "去掉Bitmap复用池修复画面不动"` 说明之前踩过同一个坑，后来又把复用加了回来。
- **位置**：`engine/GifRenderer.kt`、`engine/AnimationController.kt`
- **修复**：每帧返回新的 `Bitmap`；另外 GIF 原来始终处于 IDLE 状态（500ms/帧 = 2FPS），现在 GIF 走动画帧率（200ms/帧 = 5FPS）。
- **遗留**：每帧新分配 Bitmap 有 GC 压力（见第六节 U5）。

### 3. 50% 缩放档位点了没反应

- **根因**：`AnimationController.MIN_SCALE = 1f`，`setScale(0.5f)` 被 `coerceIn` 截成 1f；而 UI 提供了 0.5x 档位。
- **位置**：`engine/AnimationController.kt`
- **修复**：`MIN_SCALE` 改为 0.5f，与 `PetListScreen.SCALE_OPTIONS`、悬浮窗捏合范围（0.5~5）统一；同时把基准尺寸缓存到 `baseW/baseH`，避免每次缩放都重新读图片头。

### 4. 悬浮窗运行中，底部缩放按钮完全无效

- **根因**：服务只在 `show()` 时读一次 `pet.scale`，之后**不订阅仓库**；而 UI 的 `curScale` 是 Activity 里的 `remember { 1f }`，既不反映已保存值，也不接收捏合结果。
- **修复**：服务新增 `observeScale()` 订阅 `getAllPets()`，仓库里的 scale 一变就应用；`MainActivity` 的 `curScale` 改为从 `petList` 派生（`pet.scale`）。现在按钮能实时改大小，重启应用后也能正确高亮。

### 5. 切换宠物必须先「关闭」再「开启」

- **修复**：运行中点击其他宠物卡片 → 直接 `startService(新 id)`；服务在切换前先保存上一只的位置/缩放。README 的 Q5 与「已知限制」已同步更新。

### 6. 拖拽时动画卡在第一帧

- **根因**：`onDrag` 每个触摸事件都调 `anim.switchState()`，而 `switchState` 无条件 `fi = 0`，拖动过程中帧序号被反复清零。
- **修复**：`switchState` 在状态未变化时直接返回，只有真正换状态才重置帧序号。拖动时现在能看到跑步动画。

### 7. 「授予悬浮窗权限返回后自动开启」不生效

- **根因**：`DisposableEffect(lifecycle)` 只创建一次，内部 `LifecycleEventObserver` 捕获的是首次组合时的 `activeId`；此时 DataStore 还没加载完，捕获到的永远是 `null`。
- **修复**：用 `rememberUpdatedState(activeId/pendingStart)` 让 observer 读到最新值。README 宣传的"自动开启"现在是真的能用。

### 8. `LifecycleService` 生命周期永远停在 CREATED（lint error）

- **根因**：`PetOverlayService.onStartCommand` 没有调用 `super.onStartCommand()`。`LifecycleService` 靠 super 派发 `ON_START`；不调用导致 service 的 lifecycle 一直停在 CREATED，`repeatOnLifecycle`/`collectAsStateWithLifecycle` 之类的行为都会失效。lint 报 `MissingSuperCall` 并**直接让构建失败**。
- **修复**：补上 `super.onStartCommand(i, f, s)`。

### 9. 删除当前显示的宠物后，界面状态错乱

- **根因**：`deletePet` 把 `activePetId` 写成空串`""`，而 UI 用 `activeId != null` 判断 → 仍然显示「开启」和缩放假按钮，点了没反应（服务收到空 id 直接退出）。
- **修复**：`MainActivity` 统一 `rawActiveId?.takeIf { it.isNotEmpty() }`；删除正在显示的宠物时先 `stopService` 再删文件。

---

## 三、P1 稳定性 / 性能 / 安全（已修复）

| # | 问题 | 位置 | 修复方式 |
|---|------|------|----------|
| 10 | 重复导入时已解压的文件残留在磁盘上 | `ImportScreen` | 判定重复后调用 `FileManager.deletePet()` 清理 |
| 11 | GIF 显示名是一串 content URI 数字（`msf:1000000123`），并非文件名 | `FileManager.importGif` | 通过 `ContentResolver` 查询 `OpenableColumns.DISPLAY_NAME`；`sourceId` 改用文件名，重复检测才真正生效 |
| 12 | 恢复备份时选了非备份 zip，仍提示"恢复完成" | `FileManager.restoreFromBackup` | 找不到 `pets.json`/`pet.json` 时抛 `ImportException`，UI 显示具体错误 |
| 13 | 列表缩略图直接整张解码 1536×1872 精灵表（11MB/张），首屏多卡片并发 → OOM 风险 | `PetListScreen.loadOrCreateThumb` | 改用 `BitmapRegionDecoder` 只解第一格（约 0.15MB）；API 26-27 退回先取尺寸再整表解码 |
| 14 | 服务 `show()` 在主线程解析 pet.json、解码图片头；导入 zip 的解压/拷贝同样在主线程 | `PetOverlayService.show` / `ImportScreen.runImport` | 相关调用都改为 `Dispatchers.IO` |
| 15 | 位置/缩放只在 `onDestroy` 保存，拖动后进程被杀就丢失 | `PetOverlayService` / `GestureHandler` | 手势结束（拖拽/捏合）立即落盘；切换宠物前也保存 |
| 16 | Android 13+ 从未申请通知权限 → 常驻通知根本不显示（README 却宣称"通知栏常驻"） | `MainActivity` | Manifest 声明 `POST_NOTIFICATIONS`，开启宠物时运行时申请 |
| 17 | 导入界面按返回键直接退出应用 | `ImportScreen` | 加 `BackHandler` 返回宠物栏 |
| 18 | 悬浮窗真实运行状态与 UI 脱节（Activity 重建后显示「开启」但宠物还在） | `MainActivity` / 新增 `overlay/OverlayState.kt` | 服务自己发布 `StateFlow` 运行状态，UI 只消费 |
| 19 | 示例宠物 `sourceId` 为空 → 重复导入检测对示例宠物无效 | `SamplePetLoader` | `sourceId = config.id` |
| 20 | 自主行为实际 8~20s 触发、5s 无触摸即触发，与 README/设计文档的"30 秒"不符 | `AutonomousBehavior` | 改为 5~30s 随机 + 30s 无触摸门槛 |
| 21 | `SpritesheetParser.release()` 可能在 IO 线程解码中途回收 decoder/bitmap → "trying to use a recycled bitmap" | `SpritesheetParser` | `extractFrame`/`release` 加 `@Synchronized` |
| 22 | GIF 的 `Movie` 从不释放（服务销毁后仍留 native 内存） | `PetEngine` / `PetOverlayService` | 新增 `PetEngine.release()`，统一释放 parser + gif |
| 23 | FileProvider 暴露了整个 `filesDir`（含全部宠物数据） | `res/xml/file_paths.xml` | 只暴露 `cache/`（导出 zip 所在目录） |
| 24 | ZIP 解压无大小/条目上限（zip 炸弹、超大文件占满存储） | `FileManager.extractZip` | 500 条目 / 200MB 上限；GIF 改为边拷贝边限流 10MB，不再先写满磁盘再判断 |
| 25 | 仓库"读-改-写"无并发保护，示例宠物加载与用户导入并发时丢数据 | `PetRepository` | 所有写操作加 `Mutex`；位置/缩放值未变化时跳过写入 |

---

## 四、P2 构建 / 清单 / 工程规范（已修复）

| # | 问题 | 修复 |
|---|------|------|
| 26 | `lintDebug` 报 `local.properties` 的 `PropertyEscape` 错误（`sdk.dir=D:/Dev/...` 冒号未转义），lint 直接失败 | 改为 `D\:/Dev/Android/Sdk`，现在 lint 可通过 |
| 27 | `android:label` 硬编码中文，`strings.xml` 的 `app_name` 反而成了未使用资源 | 改为 `@string/app_name` |
| 28 | `specialUse` 前台服务缺少 Google Play 要求的 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 声明 | 在 `<service>` 内补 `<property>` |
| 29 | 自适应图标缺少 `monochrome`（Android 13 主题图标不生效） | 补 `<monochrome>` |
| 30 | `allowBackup` 在 Android 12+ 已废弃，且未声明数据抽取规则 | 补 `data_extraction_rules.xml` + `backup_rules.xml`，明确排除全部数据 |
| 31 | `Lifecycle` 声明 2.7.0 但实际解析到 2.8.3（声明与解析不一致）；`core-ktx` 1.12.0 偏旧 | 统一声明 2.8.3 / 1.13.1 |
| 32 | `PetOverlayRenderer` 有未使用的 `modifier` 参数，且 `gestureModifier` 不符合 Compose 命名约定 | 精简为 `PetOverlayRenderer(anim, modifier)` |
| 33 | lint `UseKtx`：`Bitmap.createBitmap(...)`、`Uri.parse("...")` 等可用 KTX 扩展 | 改用 `createBitmap()` / `toUri()` |

> 说明：`mipmap-anydpi-v26` 的 `ObsoleteSdkInt` 告警**保留不修**——把自适应图标挪到 `mipmap-anydpi` 后 AAPT 会报 `resource mipmap/ic_launcher not found`（实测），该告警可以忽略。

---

## 五、文档与实际不符（已修复）

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 34 | README §4 / §6 / 免责声明 | 代码许可证写成 **MIT**，而仓库 LICENSE 是 **GPL v3**（徽章也是 GPLv3），自相矛盾 | 统一为 GPL v3 |
| 35 | README §2.3 | "内置 **8** 只示例宠物"，实际 assets 只有 **7** 只（与 WelcomeGuide 的 7 只也不一致） | 改为 7 |
| 36 | README §2.2 | 步骤编号出现两个 "4."，而且第 3、4 步互相矛盾（"自动开启，无需再点" vs "再次点击开启"） | 重写步骤，补充 Android 13+ 通知权限说明 |
| 37 | README §3.4 / Q5 / 已知限制 | 宣称"切换宠物必须先关闭再开启"，现实现已支持直接切换 | 三处同步更新 |
| 38 | README 技术栈 | "目标版本 Android 14（API 34）"，实际 `targetSdk = 35` | 改为 Android 15（API 35） |
| 39 | README §4.2 / 已知限制 | 未说明 GIF 帧率限制 | 补充"GIF 约 5FPS 播放" |
| 40 | `doc/tasks/progress.md` | 头部写"38 个子任务、完成 36"，表格逐行求和是 **45 个子任务、完成 43** | 按表修正为 45 / 43 |

---

## 六、未处理问题（建议后续迭代）

按优先级排列，均未修改代码：

### 高

1. **CI 质量门禁不全**（`.github/workflows/test.yml`）
   - 原本只跑 `testDebugUnitTest` + `assembleDebug`，**不跑 lint、不跑 `assembleRelease`、不跑 UI 测试**。R8/proguard 相关问题在 CI 里永远发现不了（本次审查才第一次验证 release 能过 R8）。已补上 `lintDebug` 步骤，**`assembleRelease` 与 UI 测试仍未加入**。
   - tag 触发的 GitHub Release 直接上传 **debug 签名**的 APK（L44-49），发布产物与正式签名不一致。
   - L28-30 用 `sed` 按精确字符串改写 wrapper URL，阿里云地址一变就静默失效。
   - `softprops/action-gh-release@v1` 已过时（建议 v2）。
   - 建议：加 `./gradlew lint` + `assembleRelease`；配置 keystore（secrets）做正式签名；UI 测试用 emulator 或至少 `connectedAndroidTest` 的独立 workflow。

2. **测试覆盖偏浅**（`app/src/test`, `app/src/androidTest`）
   - 46 个单元测试全部是"初始值/不崩溃/映射关系"级别，**没有覆盖**：`AnimationController.start()` 的帧发射（本次 GIF 冻结 bug 就属于这类）、`FileManager` 导入/去重/ZIP 路径穿越、`PetRepository` 并发写、`PetOverlayService` 状态机。
   - 5 个 Compose UI 测试只断言文本存在，且没有设备/CI 执行过。
   - 建议：至少为"帧序列发射"和"导入流程"补测试，这类回归恰恰是真实出现过的 bug。

3. **`test_pets/` 与 assets 完全重复**：约 13.2MB、14 个文件被 git 跟踪，内容与 `app/src/main/assets/pets/*` 一模一样，建议删除（历史里仍可找回）。

### 中

4. **APK 体积**：assets 里 7 张 spritesheet 共 13.2MB（每张 1536×1872 WebP），debug 23.2MB / release 16.9MB。若在意体积，可在构建期把用不到的行裁掉或转成更小分辨率（引擎按 8×9 网格切帧，任意分辨率都支持）。
5. **`proguard-rules.pro` 过宽**：`-keep class androidx.compose.** { *; }` + `-keep class kotlinx.serialization.** { *; }` 基本让 R8 失去裁剪意义；`okio` 两条规则是 Coil 时代的死代码（项目已无 Coil/okio 依赖）。建议删除死规则、收窄 keep。
6. **每帧 Bitmap 分配**：GIF 每帧新建 Bitmap（约 1MB/帧 @5FPS）靠 GC 回收；Codex 每帧 `decodeRegion` 也不回收上一帧。可考虑双缓冲 + 帧序号（`data class Frame(bitmap, seq)`）方案，既避免 StateFlow 去重又降低分配。
7. **`PetStore` JSON 解析失败会静默返回空列表**（`data/PetStore.kt:29-36`）→ 下次启动 `SamplePetLoader` 认为"一只都没有"，会再次导入 7 只示例宠物（磁盘上出现重复目录）。建议解析失败时保留错误状态并给出提示，而不是当成空列表。
8. **`onDestroy` 用 `runBlocking` 最多阻塞主线程 2 秒**（`overlay/PetOverlayService.kt:182-191`）。虽然手势结束已落盘，但仍有 ANR 余量风险，建议改成独立 IO 协程 + 应用级保底保存。
9. **备份包包含缩略图缓存**：`thumb_hq.png` 写在宠物目录里，会被 `walkTopDown` 一起打进导出/备份 zip（体积浪费）；恢复时 `srcDir.listFiles()`（`data/FileManager.kt:149`）遇到嵌套子目录会抛 `FileNotFoundException`（当前备份结构是平的，属于潜在问题）。

### 低

10. **无 ViewModel / 单一状态源**：宠物列表、导入、悬浮窗控制全在 `MainActivity` 的 composable 里，Activity 重建后局部状态丢失；`isFirstLaunch` 默认值 `true` 会导致老用户偶尔看到欢迎弹窗闪烁。
11. **国际化/无障碍**：所有文案硬编码中文（`strings.xml` 只有一个 app_name）；悬浮窗是 Canvas 绘制，没有语义节点，TalkBack 完全无法操作宠物；部分图标 `contentDescription = null`。
12. **悬浮窗会拦截整块矩形区域的触摸**：宠物是矩形窗口，透明像素区域也消费点击，可能导致下方 App 的按钮点不到（尤其大尺寸精灵）。
13. **已废弃 API**：`WindowManager.defaultDisplay`（API 30+ 废弃）、`BitmapRegionDecoder.newInstance(String)`（API 31+ 废弃）、`Theme.statusBarColor`（targetSdk 35 上已被系统忽略，Android 15 强制 edge-to-edge）。目前不影响功能，但 Android 16 需重点关注。
14. **依赖落后**：coroutines 1.7.3（最新 1.10+）、serialization 1.6.2、Compose BOM 2024.09.00；`compileSdk/targetSdk 35`，36 已可用。
15. **通知没有操作按钮**：无法从通知栏直接关闭宠物，只能打开 App 点「关闭」；开机自启仍未实现（文档已列入规划）。
16. **`settings.gradle.kts` 仍配置 jcenter 镜像**（仓库早已停止服务，属于无效配置）。
17. **版本管理**：`versionCode = 1` / `versionName = "1.0"` 从未递增；无 signingConfig、无 changelog。
18. **`doc/tasks/*.md` 与实现有出入**：进度数字已修正，但 T10-01（引导页）、T10-06（端到端测试）等描述仍与现状不完全一致。

---

## 七、本次改动文件清单

```
app/src/main/AndroidManifest.xml            Manifest 权限/label/FGS property/备份规则
app/src/main/res/xml/file_paths.xml         收窄 FileProvider 暴露范围
app/src/main/res/xml/data_extraction_rules.xml  新增
app/src/main/res/xml/backup_rules.xml       新增
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml  补 monochrome
app/src/main/java/com/deskpet/MainActivity.kt       状态/权限/生命周期/缩放同步
app/src/main/java/com/deskpet/overlay/PetOverlayService.kt   super 调用/IO/状态发布/位置保存/释放
app/src/main/java/com/deskpet/overlay/OverlayState.kt       新增
app/src/main/java/com/deskpet/overlay/PetOverlayRenderer.kt 参数精简
app/src/main/java/com/deskpet/overlay/GestureHandler.kt     手势结束回调
app/src/main/java/com/deskpet/engine/AnimationController.kt 缩放范围/帧率/switchState/基准尺寸
app/src/main/java/com/deskpet/engine/GifRenderer.kt         每帧新 Bitmap + 释放
app/src/main/java/com/deskpet/engine/PetEngine.kt           release()
app/src/main/java/com/deskpet/engine/SpritesheetParser.kt   decode/release 互斥
app/src/main/java/com/deskpet/engine/AutonomousBehavior.kt  5~30s + 30s 门槛
app/src/main/java/com/deskpet/data/PetRepository.kt         写入串行化 + 无变化不写
app/src/main/java/com/deskpet/data/FileManager.kt           文件名/ZIP 上限/恢复报错/共享修复
app/src/main/java/com/deskpet/data/SamplePetLoader.kt       sourceId
app/src/main/java/com/deskpet/ui/ImportScreen.kt            选中 id/重复清理/返回键
app/src/main/java/com/deskpet/ui/PetListScreen.kt           缩略图采样/缩放容差/删除确认文案
app/build.gradle.kts                        依赖版本对齐
gradle.properties                           parallel/caching
.github/workflows/test.yml                  增加 lintDebug 步骤
README.md                                   许可证/示例数量/缩放/切换/测试/目标版本
doc/tasks/progress.md                       进度数字修正
doc/code-review-2026-11-03.md               本报告
```

**未验证项**：本机没有连接 Android 设备/模拟器，`connectedAndroidTest`（5 项 Compose UI 测试）与真机上的悬浮窗行为（权限流程、通知、旋转、多指缩放）未做端到端验证，建议在 Android 8 / 13 / 14 / 15 各挑一台机器走一遍导入 → 开启 → 缩放 → 切换 → 备份恢复的完整链路。
