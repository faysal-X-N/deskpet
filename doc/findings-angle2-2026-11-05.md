# DeskPet 第五轮发现处置记录（时间语义 / 兜底 / CI / 存储等角度）

> 日期：2026-11-05
> 来源：dsh 第五轮换角度审计（时间语义、异常兜底、升级回滚、存储泄漏、可达性、权限模型、电量、数据迁移）。
> 本文档逐条记录取证结论与处置：已修 / 存疑不成立 / 进 backlog。

---

## 一、已修（本轮提交）

### 1. 时间源：墙钟 → 单调时钟

- `AutonomousBehavior.kt`：默认时间源 `System::currentTimeMillis` → `{ System.nanoTime() / 1_000_000L }`。
  保持纯 Kotlin（JVM 单测零 Android 依赖）；`nanoTime` 单调且只计亮屏 uptime，
  更贴合"用户亮屏期间 30 秒没碰它"的语义。测试注入虚拟时钟不受影响。
- `GifRenderer.kt`：`System.currentTimeMillis() % movieDuration` → `SystemClock.elapsedRealtime() % movieDuration`。

### 2. 协程作用域兜底：加 CoroutineExceptionHandler

- `PetOverlayService.kt`：`sc` 增加 `CoroutineExceptionHandler`（记日志，不崩进程）。
  SupervisorJob 只隔离兄弟协程、不吞异常，此前漏网异常会崩掉前台服务进程。
- `screenReceiver.onReceive` 整体包 try/catch：广播回调不在协程里，handler 兜不到它。

### 3. 导出缓存清理

- `FileManager.exportPetToZip`：导出前清理 cacheDir 中历史 `*_export.zip`。
  之前按宠物累积常驻（每份数 MB），删宠物不清、系统仅在存储吃紧时回收。
  `DeskPet_backup.zip` 固定名覆盖，本就无害。

### 4. CI：缺正式签名包直接 fail

- `.github/workflows/test.yml` "Pick APK to release"：无 `app-release.apk` 时
  `::error::` + `exit 1`，不再回退 debug 包。debug 签名包会让已装正式版的用户
  无法覆盖升级（签名冲突），只能卸载重装丢数据，且事故静默发生。
- 取证确认：无 secrets 时 AGP 产出 `app-release-unsigned.apk`（文件名带 -unsigned），
  fail 分支能正确命中，build.gradle.kts 无需改动。

### 5. 历史文档标注过期

- `doc/tasks/progress.md`："assets 7 只"条目加注（v1.2.0 起仅保留橘团 1 只，
  老用户已导入的本地宠物不受影响）。
- 报告称 `code-review-2026-11-05.md` APK 数字（10.38/1.57）与实际不符——
  **取证不成立**：文档实写 10.31/1.66，全文无 10.38/1.57。

## 二、报告澄清与勘误

- **老用户持有 7 只已删素材**：属实且符合预期（不能删用户数据），已并入上表 5 的标注。
  对截图决策的影响：新装用户 v1.2.0 只有 1 只，商店截图仍须重拍（真机新装环境）。
- **versionCode 4 签名冲突**：v1.1.0 的 Release APK 确为 debug 签名（14.6MB，发布当天已
  用 v1.1.1 正式签名包替换附件）。若真有用户在替换前装过 v1.1.0 debug 包，升级 v1.1.1+
  会签名冲突失败，需卸载重装（本地宠物数据可提前备份）。用户量极小、窗口极短，接受该
  风险，不做数据迁移。

## 三、进 backlog（有意不排期，产品级优化）

| 项 | 说明 |
|---|---|
| 触摸目标 < 48dp | PetGridCard 导出 28dp、DockIconAction 32dp、StepButton 36dp、恢复100% 32dp；单手误触率高 |
| 双指缩放下限 | 缩到最小后窗口 < 2 指可容纳，捏不回来只能用滑块 |
| 通知权限二次引导 | POST_NOTIFICATIONS 被拒后无处提示去设置开启 |
| pets.json schema 版本 | 无 schemaVersion 字段；当前所有字段有默认值故安全，加必填字段前必须先补 |
| idle 省电 | 静止时仍 200ms 全链路（解码→发射→重组）；可改 1FPS 或无变化停发 |
| onTrimMemory | 后台被压内存时不主动释放 SpritesheetParser 缓存（≤8MB） |
| TalkBack 长按标签 | combinedClickable 无 onLongClickLabel |

## 四、验证

- `testDebugUnitTest --rerun-tasks`：全部通过
- `:app:lintVitalRelease` + `assembleRelease` + `assembleDebug`：全部通过
