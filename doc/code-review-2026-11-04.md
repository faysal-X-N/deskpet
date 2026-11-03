# DeskPet 代码审查修复记录（第二轮）

> 日期：2026-11-04
> 背景：2026-11-03 审查修复 33 项后，本轮复核修复落实情况、实际验证构建、并处理全部遗留问题与新增发现。
> 结论一句话：**昨天的 33 项修复全部核实落实；本轮又修复 16 项问题，46 项单元测试 / lint 0 errors / Debug+Release（R8）构建全部通过。**

---

## 一、验证结果

| 项目 | 结果 |
|------|------|
| `testDebugUnitTest` | 46 通过 / 0 失败 / 0 错误 |
| `lintDebug` | 0 errors / 6 warnings（均为依赖版本提醒，无实质问题） |
| `assembleDebug` | ✅ 24 MB |
| `assembleRelease`（R8，收窄 proguard 后） | ✅ 15 MB（较此前 16.9 MB 再降） |

---

## 二、新增发现并已修复（昨天报告未覆盖）

1. **zip 素材包"套一层文件夹"时导入失败**（`FileManager.importCodexPet`）
   `pet.json` 只在 zip 根目录找，而 spritesheet 却递归查找。新增 `findPetJson()` 递归查找，与 spritesheet 行为一致；恢复备份的两个分支同步修复。
2. **恢复备份遇嵌套子目录会崩**（`FileManager.restoreFromBackup`）
   `copyTo` 不能复制目录；改用 `File.copyRecursively`。
3. **同名不同内容的 GIF 被误判重复**（`FileManager.importGif`）
   GIF 的 `sourceId` 从「文件名」改为「文件名:字节数」，内容不同但同名的 GIF 不再被误拦。
4. **删光宠物后示例宠物复活 / JSON 损坏后示例重复导入**（`SamplePetLoader` + `IPetStore`/`PetStore`）
   新增持久化 `samples_loaded` 标记：只有真正加载过示例（或 assets 为空）才打标记，不再以"列表为空"作为导入依据。
5. **动画帧序号 `fi` 在 frames=0 的状态下无限自增**（`AnimationController`）
   `tf <= 0` 时重置序号并跳过本拍。
6. **交叉验证发现的自引入回归：切换 GIF 宠物可能崩溃（recycled bitmap）**（`AnimationController`/`GifRenderer`）
   双缓冲改造后，`GifRenderer.release()` 会 recycle 缓冲位图，但切换宠物时旧 ComposeView 仍挂在窗口上且 `_frame` 还持有旧帧引用，RenderThread 绘制已回收位图会崩。修复：`load()` 先清帧再创建 loader；`release()` 不再 recycle（只清引用交给 GC），彻底消除读写竞争。

## 三、昨天遗留问题处理情况

### 已修复

| # | 问题 | 修复方式 |
|---|------|----------|
| 1 | CI 发布 debug 签名 APK、不跑 release 构建 | `test.yml` 新增 `assembleRelease`；可选 secrets 正式签名（`SIGNING_KEYSTORE_BASE64` 等 4 个 secrets）；发布产物优先 release APK、无签名才回退 debug；`softprops/action-gh-release` v1→v2；wrapper sed 改为整行替换（镜像地址变化不再失效） |
| 5 | proguard 过宽（全量 keep compose/serialization + okio 死规则） | 收窄为仅 keep `com.deskpet.data.model.**`；删 okio 死规则；`assembleRelease` 实测通过且体积再降约 2MB |
| 6 | 每帧新建 Bitmap 的 GC 压力 | `AnimationController` 改发射 `Frame(bitmap, seq)`（StateFlow 按 seq 判新），`GifRenderer` 改**双缓冲**（A/B 两块位图交替复用），GIF 场景从每帧 ~1MB 分配降为零稳态分配 |
| 7 | `PetStore` JSON 解析失败静默返回空列表 | 由 #4 的 `samples_loaded` 标记兜底：损坏时不再重复导入示例宠物 |
| 8 | `onDestroy`/`onTaskRemoved` 用 `runBlocking` 阻塞主线程最多 2s | `PetRepository` 新增 `savePositionAsync()`（独立 IO 作用域），服务销毁改为异步落盘；手势结束时的同步保存仍兜底主路径 |
| 9 | 备份 zip 包含缩略图缓存 | `addPetFilesToZip` 跳过 `thumb*` 文件 |
| 12 | 悬浮窗透明像素拦截触摸 | 属于产品行为取舍（涉及 NOT_TOUCHABLE 动态切换与事件重派发，风险高），**暂不处理**，保留在已知限制 |
| 15 | 通知没有操作按钮 | 通知栏新增「关闭宠物」按钮（`PendingIntent.getService` + `ACTION_STOP`） |
| 16 | jcenter 镜像无效配置 | 从 `settings.gradle.kts` 两处移除 |
| 17 | versionCode/versionName 从未递增 | 2 / 1.1 |
| 13(部分) | `WindowManager.defaultDisplay` 废弃 | API 30+ 改用 `maximumWindowMetrics.bounds`；`BitmapRegionDecoder.newInstance(String)` 与 `statusBarColor` 保留 @Suppress（替换成本高、当前无功能影响） |
| 14(部分) | 依赖落后 | coroutines 1.7.3→1.8.1、serialization 1.6.2→1.7.3、lifecycle 2.8.3→2.8.7（保守升级，全部验证通过） |
| 3 | `test_pets/` 13.2MB 与 assets 完全重复 | 已删除 |

### 暂不处理（超出本轮范围，属功能/架构级）

- **无 ViewModel / 单一状态源**：状态全部在 MainActivity composable 里——建议后续大版本重构。
- **国际化**：文案硬编码中文、TalkBack 无语义节点——需要整体规划。
- **开机自启 / 多只同屏**：产品规划项。
- **UI 测试（connectedAndroidTest）加入 CI**：需要模拟器环境，建议单独 workflow。
- `doc/tasks/*.md` 与实现的历史出入：属于过程文档，不影响代码。

---

## 四、本轮改动文件清单

```
app/src/main/java/com/deskpet/data/FileManager.kt        pet.json 递归查找/子目录恢复/GIF sourceId/排除缩略图
app/src/main/java/com/deskpet/data/IPetStore.kt          新增 samplesLoaded
app/src/main/java/com/deskpet/data/PetStore.kt           samples_loaded 持久化
app/src/main/java/com/deskpet/data/PetRepository.kt      samplesLoaded 转发 + savePositionAsync
app/src/main/java/com/deskpet/data/SamplePetLoader.kt    标记判断替代空列表判断
app/src/main/java/com/deskpet/data/model/PetInfo.kt      sourceId 注释更新
app/src/main/java/com/deskpet/engine/AnimationController.kt   Frame(bitmap, seq) + fi 保护
app/src/main/java/com/deskpet/engine/GifRenderer.kt      双缓冲复用
app/src/main/java/com/deskpet/overlay/PetOverlayService.kt    异步落盘/规范 receiver/WindowMetrics/通知关闭按钮
app/src/main/java/com/deskpet/overlay/PetOverlayRenderer.kt   适配 Frame
app/src/main/java/com/deskpet/MainActivity.kt            showHelp 不再误清首次启动标记
app/build.gradle.kts                                     版本号/依赖升级/可选签名配置
app/proguard-rules.pro                                   收窄 keep 规则
settings.gradle.kts                                      移除 jcenter
.github/workflows/test.yml                               release 构建/可选签名/v2/容错 sed
app/src/test/java/com/deskpet/data/PetRepositoryTest.kt  InMemoryPetStore 适配新接口
test_pets/                                               删除（13.2MB 重复素材）
README.md                                                通知关闭按钮/GIF 去重规则说明
doc/code-review-2026-11-04.md                            本文档
```

## 五、联网调研交叉验证（2026-11-04 第二轮）

| 风险点 | 官方/权威依据 | 结论 |
|--------|--------------|------|
| proguard 收窄后 JSON 序列化是否安全 | kotlinlang.org 官方文档："Kotlin serialization includes default ProGuard rules, so you don't need additional setup"（唯一例外是 named companion，本项目没有） | ✅ 安全，且有 `-keep data.model.**` 双保险 |
| R8 实证检查 | mapping.txt：7 个数据模型类全部保留原名未混淆；`PetInfo$$serializer` 确认存在于 release dex | ✅ 实证通过 |
| `RECEIVER_NOT_EXPORTED` 能否收到 SCREEN_ON/OFF | developer.android.com 广播概览："NOT_EXPORTED 能接收部分系统广播（system UID 发出），仅收不到高特权应用（蓝牙/电话等非系统 UID）的广播"；SCREEN_ON/OFF 由 system server 发出 | ✅ 用法正确 |
| 通知按钮 `PendingIntent.getService` 是否违反后台启动限制 | 官方 FGS 启动豁免列表："user performs an action on a UI element (notification/widget)" 属于豁免；且服务已在运行，只是投递 onStartCommand | ✅ 合法 |
| `specialUse` FGS 类型合规 | 官方文档：需 FOREGROUND_SERVICE_SPECIAL_USE 权限 + PROPERTY_SPECIAL_USE_FGS_SUBTYPE property（均已具备）；**上架 Google Play 时还需在 Play Console「政策 > 应用内容」声明 FGS 类型** | ✅ 代码合规；⚠️ 上架时需在 Console 声明 |
| Android 15 强制 edge-to-edge | res 中无 statusBarColor 残留；UI 已用 `statusBarsPadding()` 适配 | ✅ 无影响 |

## 六、遗留风险（静态验证覆盖不到，需真机）

1. release APK 运行时行为（序列化链路已实证保留，但建议真机装 release 包走一遍存档/备份）；
2. GIF 双缓冲极端卡顿下单帧撕裂（不崩溃）；
3. 真机回归：导入嵌套文件夹素材包、GIF 切换、通知栏关闭按钮、备份恢复。

## 六、第三轮极端视角审查（2026-11-04 追加）

假设"最坏情况"逐点攻击：并发竞争、恶意构造输入、磁盘写满、进程中途被杀。

| # | 严重度 | 问题 | 修复 |
|---|--------|------|------|
| 1 | 🔴 安全 | `restoreFromBackup` 的 `pet.id` 来自备份 JSON（外部输入），直接拼进 `File(petsDir, pet.id)`，含 `../` 的 id 可写穿到应用私有目录任意位置 | 校验 id 无 `/` `\` `..`，且 destDir canonical path 必须位于 pets 目录内，否则跳过 |
| 2 | 🟠 并发 | `GifRenderer.load/getFrame/release` 无同步：切换宠物时 IO 线程 load 与动画循环 IO block 并发访问 movie/buffers（去 recycle 后不崩，但属数据竞争，最坏撕裂帧） | 三个方法全部 `@Synchronized` |
| 3 | 🟠 并发 | `AnimationController.loader`/`frameSeq`：Main 循环读、IO reload 写，无 volatile（JMM 数据竞争） | 两个字段加 `@Volatile` |
| 4 | 🟡 健壮 | `copyRecursively` 返回 Boolean 未检查：磁盘满时默认静默跳过失败项，"恢复成功"但文件缺失 | 检查返回值，失败清理半成品并抛错 |
| 5 | 🟡 健壮 | `pet.json` 未限大小：恶意 zip 内嵌 200MB JSON → `readText` OOM | 导入与恢复两处加 1MB 上限 |
| 6 | 🟡 健壮 | GIF 导入的 `.tmp` 在进程被杀时永久残留累积 | 导入开始时清理超过 24h 的 `.tmp` |

**复查为安全的极端场景**：SpritesheetParser 缓存访问全部位于 `@Synchronized` 的 extractFrame/release 之内；shareZip 仅从 Activity 上下文调用；ACTION_STOP 不取消 in-flight job 的窗口由 onDestroy 的 sc.cancel() + removeOverlayView 兜底；切换宠物时旧 loader 已被循环捕获的在途帧，经 #2/#3 修复后最坏多显示一帧旧画面，无崩溃路径。

**验证**：46/46 测试通过、lint 0 errors、assembleDebug + assembleRelease BUILD SUCCESSFUL。

## 七、极端测试执行（2026-11-04 追加）

按调研结论执行"恶意文件模糊测试"（最推荐项）。将 zip 解压核心从 FileManager 抽取为纯 JVM 类
`ZipExtractor`（FileManager 委托调用，行为不变），新增 `ZipExtractorFuzzTest`（12 个用例）在测试内
现场生成攻击文件直接轰真实解析代码。

**攻击用例与结果（全部通过）：**

| 攻击手法 | 结果 |
|---|---|
| 路径穿越 `../evil.txt` / `a/../../evil.txt` | ✅ 拒绝且目标目录外无文件产生 |
| 绝对路径 `/data/...` 条目 | ✅ 无法逃逸目标目录（File(parent,child) 字符串拼接语义） |
| zip 炸弹（150MB+60MB 零流，磁盘仅 ~200KB） | ✅ FileTooLarge 拒绝 |
| 501 个条目超量 | ✅ InvalidFormat 拒绝 |
| 空文件名条目 | ✅ 抛异常不崩溃 |
| 手工构造的重复条目 zip（绕过 ZipOutputStream 限制） | ✅ 不崩溃、后写覆盖 |
| 100 层嵌套目录合法 zip | ✅ 正常解压（防误伤） |
| pet.id 白名单（`../`、`a/b`、反斜杠、空值等 9 组） | ✅ 全部正确拦截 |
| 伪装 GIF 的 JPEG / 残缺 GIF 头 | ✅ 魔数校验拦截 |
| 单文件复制超限（GIF 10MB 上限） | ✅ 拒绝；正常文件不受影响 |

**测试构造过程中的教训**：手拼 zip 字节必须小端序（DataOutputStream 是大端序会导致毒文件无效）；
ZipInputStream 串接的多个 zip 只读第一个（EOCD 停止）；JVM File(parent,child) 对 "/" 开头条目是
拼接语义，落点在目标目录内部——三条都已写进测试注释，防止后人重蹈。

**未执行项（需要真机/模拟器，当前未连接）**：进程死亡测试（`am kill` / force-stop / 不保留活动）、
Monkey/Fastbot 压测、磁盘写满、LeakCanary 长跑。操作命令见对话调研记录，设备接入后可直接执行。

**验证**：58/58 测试通过（46 旧 + 12 模糊）、lint 0 errors、debug/release 构建成功。

## 八、第四轮逻辑专查（2026-11-04 追加）

逐行推演运行时行为（状态机时序、并发交错、数据流闭环），修复 6 个确定的逻辑 bug：

| # | 严重度 | 问题 | 修复 |
|---|--------|------|------|
| 1 | 🔴 高 | `bounce()` 无互斥：快速连点 GIF 宠物时多个弹跳协程各自捕获基准 wy，恢复的是错误基准 → **每连点一次位置永久漂移 20px，连点会一直漂到屏幕顶** | 单飞互斥（进行中忽略新点击）+ finally 保证恢复基准位 |
| 2 | 🔴 高 | `displaySize()` 误用 `maximumWindowMetrics`（不随旋转变化，第三轮换 API 时引入的回归）→ 旋转屏幕后 clamp 维度错乱，**宠物被夹到屏幕外找不到** | 改用 `currentWindowMetrics`（跟随当前旋转，与旧 defaultDisplay.getSize 语义一致） |
| 3 | 🟠 中 | `show()` 中 `add()` 失败（无悬浮窗权限，如权限被撤销后 START_STICKY 自动重启）只标记状态不停服 → **通知栏残留"正在运行"、前台服务空转耗电** | 失败路径补 `stopSelf()` |
| 4 | 🟠 中 | `AnimationController` 状态帧数 tf<=0 时只重置序号不推进状态机 → 宠物**永久冻结在旧画面**，且自动行为/单击互动（都等 IDLE）全部失效 | tf<=0 立即回 IDLE |
| 5 | 🟡 低 | 灭屏只停动画循环，`AutonomousBehavior` 继续切状态 → 亮屏后宠物播放一段"没来由"的动作 | SCREEN_OFF 同时停 auto，SCREEN_ON 一起恢复 |
| 6 | 🟡 低 | `PetStore` 宠物列表 JSON 损坏即静默返回空列表 → 用户所有宠物"集体消失"（文件还在） | 解析失败回退最后成功的列表（锁保护），下次写入自动自愈 |

**推演确认无问题的链路**：切换宠物时先存旧位置再换 petId；删除当前宠物与服务 onDestroy 的保存/删除交错（savePosition 查不到 id 时不写，双 Mutex 串行化兜底）；切换宠物时 job 取消与在途 reload 交叠（最终一致）；observeScale 与手势缩放的回环（savePosition 后差值 <0.001 不再触发）；捏合增量基准（首事件只记录 span 不缩放）；通知"关闭宠物" stopSelf 后 START_STICKY 不复活。

**记录在案、暂不处理**：①拖拽进行中按下第二指会同时触发捏合（两手势无互斥，正常操作很少见）；②`onPetSelected` 在服务启动完成的几百 ms 窗口内点另一只宠物不会切换（setRunning 尚未发布）；③`onScale` 超时 2s 时 UI 缩放高亮与悬浮窗可能短暂不一致。

**验证**：58/58 测试通过、lint 0 errors、assembleDebug + assembleRelease BUILD SUCCESSFUL。

## 九、第五轮：悬浮窗权限链路专查（2026-11-04 追加）

从「点开启 → 跳设置 → 授权 → 自动续启 → 服务挂窗 → 权限被撤销 → 自动重启」全链路推演。Manifest 声明核实完整（SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / specialUse + PROPERTY_SPECIAL_USE_FGS_SUBTYPE）。

| # | 严重度 | 问题 | 修复 |
|---|--------|------|------|
| 1 | 🟠 中 | 服务端 `add()` 无权限时尝试跳转设置页：Android 10+ 后台启动 Activity 限制下，无 SYSTEM_ALERT_WINDOW 的 App 不豁免 → 该跳转**永远被静默拦截**，是反作用的死代码 | 删除跳转，权限引导统一由 MainActivity 的 pendingStart + ON_RESUME 流程负责 |
| 2 | 🟠 中 | `pendingStart` 在用户未授权返回时永不清除 → 之后任何一次无关的授权都会让宠物"凭空出现" | ON_RESUME 时无论授权与否都清标记；已授权则续启 |
| 3 | 🟡 低 | 权限被撤销后（系统立即移除悬浮窗但服务还在），START_STICKY 重启 / 切换宠物时 `show()` 先白做一轮磁盘加载才发现没权限 | `show()` 入口先查权限，快速失败 stopSelf |
| 4 | 🟡 低 | `onTaskRemoved` 在 stopWithTask="true" 下永远不会被回调（划掉任务系统直接停服），注释误导 | 加注释说明实际由 onDestroy 兜底保存，方法保留为双保险 |

**推演确认无问题的环节**：Manifest 声明完备；MainActivity 点开启时的前台 startService（所有调用点都在前台，不触发 Android 12+ FGS 后台启动限制）；从设置页返回的 ON_RESUME 自动续启用 rememberUpdatedState 读最新值；通知栏关闭按钮的 PendingIntent.getService 属官方豁免场景；划掉任务后位置由 onDestroy 的异步保存兜底；撤销权限后服务不自知的窗口期有两条自愈路径（点关闭 / 点任意宠物卡片触发快速失败）。

**验证**：58/58 测试通过、lint 0 errors、assembleDebug + assembleRelease BUILD SUCCESSFUL。

## 十、真机反馈修复：冷启动"闪窗"（2026-11-04 追加）

用户真机反馈：第二次启动起，每次冷启动都有一个窗口闪过。根因是 Compose + Flow 初值模式错误：

| # | 问题 | 修复 |
|---|------|------|
| 1 | `isFirstLaunch` 以 `true` 为初值：DataStore 从磁盘读出真实值前的头几帧，欢迎引导弹窗先渲染再消失 → **每次冷启动闪现引导窗** | 改三态（null=未知），读到真实值前不渲染弹窗 |
| 2 | 同类：宠物列表以 `emptyList` 为初值 → 冷启动瞬间"还没有宠物"空状态闪现 | 新增 `petListReady` 标记，数据未到时保持空白 |

**教训记录**：`collectAsStateWithLifecycle(默认值)` 的默认值若与真实值可能不同，凡是"默认值会渲染出可见 UI"的场景都会闪现——默认值必须代表"什么都不显示"的中性状态。

**验证**：58/58 测试通过、lint 0 errors、assembleDebug 成功。

## 十一、缩放交互重构：档位按钮 → 滑块（2026-11-04 追加）

用户反馈点按档位按钮切缩放不好用。全网调研结论（VPet / Shimeji / 悬浮窗工具类 / UX 研究）：
- 主流桌宠的缩放都是"设置里滑块连续调节"（VPet、Shimeji 的 Shimeji Size 滑块一致）
- UX 研究（uxtigers/NNG）：直接操纵（捏合）直觉但慢，相机 App 加"1x 快捷重置"后体验最佳；
  可达性上必须保留一个非捏合的缩放途径；连续滑动缩放要防"过冲"
- 因此淘汰 6 档按钮组（跳变不跟手、占两行、与捏合上限 500% 不一致、非档位值不高亮），
  改为：**百分比标签 + Slider（50%~500%，与引擎/捏合一致）+ 100% 一键重置**

实现细节：拖动滑块期间只更新本地值（避免高频写 DataStore），松手才落库；
悬浮窗侧经由既有 observeScale 自动跟随；repo 值变化通过 remember(curScale) 回同步滑块。
引导文案与相关注释同步更新。

**验证**：58/58 测试通过、lint 0 errors、assembleDebug 成功。

## 十二、滑块重构交叉复查（2026-11-04 追加）

对第十一节的滑块改动做交叉复查，发现并修复 1 个确定性 bug + 1 个启动边角：

| # | 问题 | 修复 |
|---|------|------|
| 1 | 🔴 松手弹回：`dragging=false` 在 `onValueChangeFinished` 中先于 DataStore 写入完成，重组时 `shown` 回退到旧 repo 值 → **每次松手滑块先弹回旧位置、几十毫秒后再跳到新值** | 改为 value 始终用本地值，repo 变化经 `LaunchedEffect(curScale)` 同步，拖动中忽略同步（同时防捏合落库致拇指跳动） |
| 2 | 🟡 启动跳动：activeId 可能先于列表数据到达，滑块先按默认 100% 渲染、数据到达后跳真实值 | 滑块渲染条件加 `!isLoading` |

**核实无问题**：setScale 原样落库不做夹值，引擎限幅 0.5~5 与滑块范围一致；SCALE_OPTIONS/FilterChip 无残留；100% 按钮先乐观更新本地值再落库，响应即时。

**已知边界（记录在案）**：拖动中手势被系统事件打断时 `onValueChangeFinished` 可能不回调 → `dragging` 卡 true，外部缩放变化暂不同步到滑块；用户下次触摸滑块即自愈，可接受。

**验证**：58/58 测试通过、lint 0 errors、assembleDebug + assembleRelease BUILD SUCCESSFUL。

## 十三、UI 美化：缩放卡片 + 顶栏按钮（2026-11-04 追加）

用户反馈按钮和滑块 UI 太丑。全网调研（M3 官方规范 + M3 Expressive + Compose 自定义滑块实践）落地：

**滑块 → ScaleCard 卡片组件**：
- 卡片容器（primary 6% 透明度圆角 16dp），标签行"宠物大小" + 实时百分比胶囊 + Refresh 一键回原大
- 调节行：[−] 滑块 [+]，两端加减号微调（±10%，M3 明确推荐此布局）
- M3 Expressive 风格：8dp 粗圆角轨道（活跃 primary / 静默 primary 16%）、20dp 圆形拇指带 2dp surface 描边、100% 处刻度点
- 状态逻辑沿用第十二节的 LaunchedEffect 同步模式（无松手弹回）

**顶栏按钮**：
- "关闭"由 error 实心红改为 errorContainer 色调按钮 + Close 图标（视觉降噪，M3 惯例）
- "开启"加 PlayArrow 图标；帮助"?"文字按钮改为标准 Info 图标

**依赖**：加 material-icons-extended（BOM 管理）；debug 包 24→35MB（未压缩），release 仍 15MB（R8 裁剪）。

**验证**：58/58 测试通过、lint 0 errors、双 APK 构建成功。
