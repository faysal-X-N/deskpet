# DeskPet 代码审查修复记录（第三轮）

> 日期：2026-11-05
> 背景：复核 11-04 两轮修复的落实情况，并针对「CI 全绿但测试早已编不过」这一系统性盲区做全面体检。
> 结论一句话：**测试全绿是真的，但它只覆盖 CI 跑过的那几件事。本轮修掉 1 个构建级断裂、4 个崩溃/内存隐患、
> 4 个状态一致性与输入校验缺陷、1 组无效测试，并清掉内置第三方角色素材。**

---

## 一、验证结果（全部本地实跑，非推测）

| 项目 | 结果 |
|------|------|
| `testDebugUnitTest --rerun` | **67 通过 / 0 失败 / 0 错误**（强制重跑，不吃缓存） |
| `assembleDebugAndroidTest` | ✅ 通过（修复前 **FAILED**，见第二节） |
| `lintDebug` | 0 errors / **8 warnings**（修复前 14；逐条见第八节） |
| `assembleDebug` | ✅ **10.38 MB**（原 13.99 MB） |
| `assembleRelease`（R8） | ✅ **1.57 MB**（原 5.31 MB） |

体积下降来自移除内置第三方角色素材（约 3.6MB WebP）与冗余位图启动图标。

---

## 二、🔴 构建级断裂：androidTest 三个提交以来一直编不过

**现象**

```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: .../PetListScreenTest.kt:38:21 No value passed for parameter 'isLoading'.
```

**根因链**

1. `2849493`（UI 全面美化）给 `PetListScreen` 新增必填参数 `isLoading: Boolean`（无默认值），
   同一次提交**只改了主代码，没改测试**。
2. `src/androidTest` 自 `0f967c0`（Initial commit）后再没被任何人修改过。
3. CI 只跑 `testDebugUnitTest / lintDebug / assembleDebug / assembleRelease`，
   **没有任何一步会编译 `src/androidTest`**——`assembleDebug` 不会顺带编译它，
   `lintDebug` 只做静态分析（那一轮 `lintAnalyzeDebugAndroidTest` 是通过的，不做类型检查）。
4. 于是 33 个提交、30 次 CI，全绿。

**为什么这不是「环境问题」**：远端 `5abdcc4` 的 `PetListScreen` 第 81 行 `isLoading: Boolean,` 无默认值，
远端 `PetListScreenTest` 第 27 行起的调用没有传它。Kotlin 必传参数漏传 = 编译错误，无法回避。

**修复**

- `PetListScreenTest` 补齐 `isLoading = false`；
- 三条陈旧文案断言改为真实文案（`导入宠物` / `开启宠物` / `关闭宠物`）；
- 开启/关闭按钮位于收纳坞折叠面板内，测试先 `onNodeWithContentDescription("展开控制").performClick()` 再断言；
- **CI 新增 `./gradlew assembleDebugAndroidTest`**，此类漂移以后当场变红。

> ⚠️ 这个 workflow 步骤与测试文件必须同一次提交推送，否则下一个 CI run 会真的变红。

---

## 三、🟠 崩溃隐患：宠物列表出现重复 id

`LazyVerticalGrid(items(key = { it.id }))` 遇到重复 key 会直接抛
`IllegalArgumentException: Key ... was already used`。

产生重复 id 的真实路径：`addPetDirect`（保持原 ID 写入）被两条可能重入的流程调用——

- 首次启动 `LaunchedEffect(Unit) { SamplePetLoader.loadSamplePets(...) }`：旋转屏幕会重建组合、
  再次执行，而 `samples_loaded` 标记此时尚未落盘；
- 备份恢复（虽已有 `getPet(id) != null` 检查，但那是"读-判断-写"三步，没有原子性）。

**修复**：`PetRepository.addPetDirect` 在写锁内按 id 去重，变为幂等操作。
新增两条单测 `addPetDirect is idempotent for the same id` / `keeps original id and insertion order`。

---

## 四、🟠 内存：列表缩略图解码没有上限

`decodeFirstFrame` 只在 API 28+ 走区域解码，且**没有 inSampleSize**。
导入的素材尺寸不受限（zip 只限总量 200MB，一张 4000×4500 的平坦色图压缩后仅几百 KB 就能过），
单格 500×500 起步，列表首屏多张并发解码足以 OOM——而渲染端 `SpritesheetParser` 早已有
`MAX_FRAME_DIM = 1024` 的同款防护，两处标准不一致。

**修复**：

- 缩略图解码复用 `SpritesheetParser.frameSampleSize`，单帧按 2 的幂降到 1024 以内；
- 顺带修掉一处连带 bug：整表回退路径原本用**源图**的 `cw/ch` 去切**已降采样**的位图，
  会切到表外触发 `IllegalArgumentException`。

---

## 五、🟡 输入校验：损坏的 GIF 能一路进到宠物列表

`importGif` 只校验 6 字节魔数（`GIF87a` / `GIF89a`），截断文件、只有头的样本全部放行。
用户会得到一只"看起来导入成功、点开启才报加载失败"的宠物。

**修复**：用系统唯一 GIF 解码器 `Movie.decodeStream` 试解一次，拿不到帧或宽高非法即拒导入。

---

## 六、🟡 状态一致性：撤销悬浮窗权限后界面还在说谎

用户在系统设置里关掉「显示在其他应用上层」时，系统会移除窗口，但：

- 前台服务还活着、通知栏还写着"正在运行"；
- `OverlayState.running` 仍是 true，列表页显示"运行中"；
- `overlay_auto_resume` 仍是 true，下次冷启动会拿没有权限的状态去拉起宠物。

**修复**：`MainActivity` 的 ON_RESUME 观察者增加一次校准——已运行但无权限时 `stopOverlay()`，
同时清掉恢复意图（用户刚撤销权限，不该在下次启动时把宠物"拉回来"）。

---

## 七、🟡 测试有效性：4 个自治行为测试一直是空转

`AutonomousBehaviorTest` 里每个用例都 `TestScope(StandardTestDispatcher())`——
**不传 scheduler 会新建一个独立调度器**，`advanceTimeBy` 只推进测试自己那个，
被测协程永远停在起点。所有"不会触发"的断言因此恒真，实现改成什么样都能过。

更根本的问题：`AutonomousBehavior` 用 `System.currentTimeMillis()` 判断"空闲 30 秒"，
即便共享了调度器，虚拟时间推进得再多，真实墙钟也没走 30 秒，门槛永远不成立。

**修复**

- `AutonomousBehavior` 的时间源改为可注入（`now: () -> Long = System::currentTimeMillis`），
  生产行为零变化，测试注入 `testScheduler.currentTime`；
- 测试改为共享调度器 + 虚拟时钟，并把"永远不触发"的断言换成**双向验证**：
  - `never switches before the shortest random interval`（4.9s 内不触发）
  - `fires once the pet has been idle past the threshold`（61s 内必然触发一次）
  - `does not switch when state is not IDLE`
  - `user touch postpones the next behavior`（触摸重置空闲窗口）
  - `stopped job does not trigger`

---

## 八、🟠 合规：内置素材里的第三方角色形象

`assets/pets/` 原有 8 只示例：feibi-2 / feixue / furina / hutao / ikun / jiyi / yi-bu / jutuan。
其中前 7 只来自 Codex Pet 社区，包含《原神》等第三方角色形象。

把第三方角色素材打进 APK 分发有两层问题：

1. **版权**：GPL 只覆盖本项目自己的代码与原创美术，不覆盖他人角色形象；
2. **上架**：IzzyOnDroid 会核对"无专有组件"，内置知名角色素材是典型争议项。

**处理**：只保留本项目原创的「橘团」，其余 7 个目录从 `assets` 删除。
格式支持、导入、渲染、备份恢复全部不变；旧版用户因 `samples_loaded` 已落盘，升级后不会重复加载也不丢宠物。
那七只仍可从 git 历史（`5abdcc4`）取出、打成 zip 自行导入，详见 `doc/ORIGINAL_ASSETS.md`。

配套改动：README §2.3 / §4 知识产权声明、`WelcomeGuide` 文案、fastlane `4.txt` 更新日志、版本号 1.2.0（versionCode 4）。

---

## 九、🟢 体验与无障碍

| 项 | 问题 | 修复 |
|----|------|------|
| 通知大图 | 只有 `drawable-xxxhdpi` 一份 108×108（等效 36dp），展开通知时被放大 1.8 倍，肉眼可见发虚 | 从原始生成图重渲染 64dp 全密度（64/96/128/192/256），`.workbuddy/make_notif_large.py` 可复现 |
| 缩放加减按钮 | `StepButton(symbol, desc, ...)` 的 `desc` 是**死参数**，按钮里只有一个「−」「＋」符号，TalkBack 读不出动作含义 | `clickable(onClickLabel = desc)` + `semantics { contentDescription = desc }` |
| 导出撞名 | 两只同名宠物（都叫 `cat.gif`）导出到同一个缓存文件名，后一次会写坏正在分享的那份 | 文件名带 id 前 8 位，并对空名/超长名兜底 |
| 文件选择器 | 备份 zip / GIF 只给单一 MIME，部分机型与网盘把 zip 报成 `application/octet-stream`，选择器里灰掉选不中 | 两个 launcher 都补 `application/octet-stream` |
| 帧渲染分配 | `PetOverlayRenderer` 每帧 `asImageBitmap()` 新建包装对象（5FPS 下持续 GC 压力） | `remember(frame)` 缓存 |
| 冗余资源 | 5 份旧版位图启动图标（`mipmap-*/ic_launcher.png`）与自适应图标并存，minSdk 26 下纯冗余 | 删除 5 份 PNG（省 64KB，清掉 `IconXmlAndPng`）。**目录名 `mipmap-anydpi-v26` 保留**——按 lint 建议改名成 `mipmap-anydpi` 后，AGP 8.13 的资源合并阶段直接丢弃该目录，链接期报 `AAPT: error: resource mipmap/ic_launcher not found`（clean 重建同样复现），已实测回退 |

---

## 十、剩余 lint 警告与不处理的理由

| 警告 | 数量 | 处理 |
|------|------|------|
| `OldTargetApi`（targetSdk 35 < 36） | 1 | 保留。升 36 涉及 Android 16 大屏方向限制、预测性返回默认开启等行为变更，需真机回归；且本机 SDK 只装了 android-35 |
| `GradleDependency` / `NewerVersionAvailable` | 3 | 保留。均为"有更新版本"提示，跨大版本升级需单独验证（coroutines 1.11 / serialization 1.12 / compileSdk 36） |
| `ModifierParameter`（私有 `PetDock` 的 modifier 不在首位） | 1 | 保留。Kotlin 要求带默认值的参数必须排在无默认值参数之后，重排会牵动全部回调签名，收益极低 |
| `ObsoleteSdkInt`（`mipmap-anydpi-v26` 的 v26 限定符） | 1 | **保留，lint 误报**。按建议改成 `mipmap-anydpi` 后 AGP 8.13 资源合并直接丢弃该目录，链接期 `resource mipmap/ic_launcher not found`（clean 重建同样失败），实测回退 |
| `UseKtx` | 0（已修） | `SpritesheetParser` 改用 `androidx.core.graphics.createBitmap` |

真实剩余 8 条中，5 条是版本提示，2 条是约定类建议，1 条是资源限定符误报（附实测证据）；
`IconXmlAndPng` 与 `UnusedAttribute` 已在本轮清掉。

---

## 十一、改动文件清单

```
.github/workflows/test.yml                        新增 assembleDebugAndroidTest 守护步骤
app/src/androidTest/.../PetListScreenTest.kt       补 isLoading、改真实文案、先展开收纳坞
app/src/main/java/com/deskpet/MainActivity.kt      撤销权限校准 + 内置素材文案
app/src/main/java/com/deskpet/data/FileManager.kt   GIF 可解码校验 + 导出文件名去重
app/src/main/java/com/deskpet/data/PetRepository.kt addPetDirect 幂等
app/src/main/java/com/deskpet/engine/AutonomousBehavior.kt  时间源可注入
app/src/main/java/com/deskpet/engine/SpritesheetParser.kt   KTX createBitmap
app/src/main/java/com/deskpet/overlay/PetOverlayRenderer.kt 帧包装对象缓存
app/src/main/java/com/deskpet/overlay/PetOverlayService.kt   抑制 RtlHardcoded（Gravity.LEFT 是有意为之）
app/src/main/java/com/deskpet/ui/ImportScreen.kt    MIME 补 octet-stream
app/src/main/java/com/deskpet/ui/PetListScreen.kt   缩略图降采样 + StepButton 语义 + 清理无用 import
app/src/test/.../AutonomousBehaviorTest.kt         重写为共享调度器 + 虚拟时钟
app/src/test/.../PetRepositoryTest.kt              addPetDirect 幂等/顺序 2 条
app/src/main/AndroidManifest.xml                   tools:ignore=UnusedAttribute（附理由）
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml   （保留原目录名，改名实测会丢资源）
app/src/main/res/mipmap-*/ic_launcher.png           删除 5 份旧版位图启动图标
app/src/main/res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_notif_large.png   64dp 全密度重制
app/build.gradle.kts                               versionCode 4 / versionName 1.2.0
app/src/main/assets/pets/                          删除 7 个第三方素材包（保留 jutuan）
fastlane/metadata/android/{zh-CN,en-US}/changelogs/4.txt   新版本更新日志
doc/ORIGINAL_ASSETS.md                             记录素材移除决策与找回方式
doc/code-review-2026-11-05.md                      本文档
README.md                                          示例数量 / 知识产权 / 测试数量
```

---

## 十二、遗留风险（静态验证覆盖不到，需真机）

1. **撤销悬浮窗权限**的校准只在回到前台时触发，后台撤销仍会短暂显示"运行中"（无系统广播可用）；
2. **GIF 双缓冲**在极端卡顿下的单帧撕裂（不崩溃，持续观察）；
3. **UI 测试**已编译通过，但 5 条断言的真机执行结果未验证（本机无连接设备/模拟器）；
4. **v1.2.0 真机回归清单**：首次启动只有一只内置宠物、导入社区素材包、GIF 导入与切换、
   通知栏关闭按钮、备份/恢复、横竖屏旋转、省电模式下自主行为停走。
