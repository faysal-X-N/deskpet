package com.deskpet.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deskpet.data.FileManager
import com.deskpet.data.PetRepository
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 导入结果：数据管道在 NonCancellable 区跑完，把结论带回可取消的 UI 区 */
private sealed class ImportResult {
    data class Success(val pet: PetInfo) : ImportResult()
    object Duplicate : ImportResult()
    data class Failure(val error: ImportException) : ImportResult()
}

/**
 * 进程级导入互斥锁：uiMode/fontScale/locale 等配置变化会重建 Activity，
 * remember 的 isImporting 随之归零而旧协程还在 NonCancellable 区跑——
 * 用户此时再点导入同一个包，两次 existsBySourceId 都判定"不存在"就会进两份。
 * 锁提升到文件顶层（类加载器级单例）才能跨 Activity 重建存活。
 */
private val importMutex = Mutex()

@Composable
fun ImportScreen(
    petRepository: PetRepository,
    onImportSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val fileManager = remember { FileManager(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isImporting by remember { mutableStateOf(false) }

    BackHandler(enabled = !isImporting) { onDismiss() }

    /**
     * 统一的导入流程。
     * 注意：addPet() 会生成新的 UUID，之后必须用**返回的** pet 设置选中，
     * 否则 activePetId 指向一个不存在的 id，界面上点「开启」会什么都不发生。
     *
     * 结构：importMutex.withLock 在外层（"等锁"可取消，导入排队时返回键仍有效，
     * 不会把用户锁在导入页），NonCancellable 只包数据管道（"跑管道"不可取消：
     * 导入中旋转/切深色模式会重建 Activity，rememberCoroutineScope 随组合销毁
     * 被取消——zip 可能已解压完但 addPet 没执行，磁盘上留下永远删不掉的孤儿目录。
     * 管道跑完再回可取消区做 UI 反馈，UI 上下文已取消时 snackbar 静默跳过，无副作用）。
     */
    fun runImport(uri: Uri, importer: suspend (Uri) -> PetInfo) {
        scope.launch {
            isImporting = true
            try {
                val result = importMutex.withLock {
                    withContext(NonCancellable) {
                        var imported: PetInfo? = null
                        try {
                            // 解压 / 拷贝文件是磁盘 IO，必须离开主线程
                            imported = withContext(Dispatchers.IO) { importer(uri) }
                            if (petRepository.existsBySourceId(imported.sourceId)) {
                                // 文件已经解压到磁盘了，重复导入要把它们清掉，避免残留
                                withContext(Dispatchers.IO) { fileManager.deletePet(imported) }
                                ImportResult.Duplicate
                            } else {
                                val added = petRepository.addPet(imported)
                                petRepository.setActivePet(added.id)
                                ImportResult.Success(added)
                            }
                        } catch (e: ImportException) {
                            // 失败路径也要清掉已解压的文件：磁盘满/DataStore 写失败时
                            // 不清理会留下永远删不掉的孤儿目录（列表里看不见）
                            imported?.let {
                                runCatching { withContext(Dispatchers.IO) { fileManager.deletePet(it) } }
                            }
                            ImportResult.Failure(e)
                        } catch (e: Exception) {
                            imported?.let {
                                runCatching { withContext(Dispatchers.IO) { fileManager.deletePet(it) } }
                            }
                            ImportResult.Failure(ImportException.ExtractionFailed(e))
                        }
                    }
                }
                when (result) {
                    is ImportResult.Success -> {
                        snackbarHostState.showSnackbar("导入成功：${result.pet.displayName}")
                        onImportSuccess()
                    }
                    ImportResult.Duplicate -> snackbarHostState.showSnackbar("该宠物已存在")
                    is ImportResult.Failure ->
                        snackbarHostState.showSnackbar(result.error.message ?: "导入失败")
                }
            } finally {
                isImporting = false
            }
        }
    }

    val codexPetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { runImport(it) { u -> fileManager.importCodexPet(u) } }
    }

    val gifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { runImport(it) { u -> fileManager.importGif(u) } }
    }

            val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    scope.launch {
                        isImporting = true
                        try {
                            // 同 runImport：恢复的数据管道不可取消，防孤儿文件；
                            // 且与导入共用一把互斥锁，防 Activity 重建后并发写列表。
                            // 锁在 NonCancellable 外层：等锁阶段可取消，恢复排队时返回键有效
                            importMutex.withLock {
                                withContext(NonCancellable) {
                                    withContext(Dispatchers.IO) { fileManager.restoreFromBackup(it, petRepository) }
                                }
                            }
                            snackbarHostState.showSnackbar("恢复完成")
                            onImportSuccess()
                        } catch (e: ImportException) {
                            snackbarHostState.showSnackbar(e.message ?: "恢复失败")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("恢复失败：${e.message}")
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            // Scaffold 的 padding 已含状态栏 inset，再叠 statusBarsPadding 会双重留白
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("导入宠物", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "三种方式把新宠物带回家",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            ImportOption(
                icon = "🎨",
                title = "Codex Pet 素材包",
                subtitle = "标准 .zip（含 pet.json），支持 9 种动画",
                filled = true,
                enabled = !isImporting
            ) { codexPetLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            Spacer(Modifier.height(12.dp))

            ImportOption(
                icon = "📷",
                title = "GIF 动图",
                subtitle = "任何 .gif 文件，即导即用",
                filled = false,
                enabled = !isImporting
            ) { gifLauncher.launch(arrayOf("image/gif", "application/octet-stream")) }
            Spacer(Modifier.height(12.dp))

            ImportOption(
                icon = "💾",
                title = "恢复备份",
                subtitle = "从 DeskPet_backup.zip 一键还原全部宠物",
                filled = false,
                enabled = !isImporting
            // octet-stream：不少机型/网盘把 zip 报成未知二进制类型，只给
            // application/zip 会让备份包在选择器里灰掉选不中
            ) { backupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }

            if (isImporting) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在导入…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
            // 导入中禁用返回：中途退出组合会取消协程，虽然数据管道已 NonCancellable
            // 兜底，但用户会失去结果反馈，不如等它跑完
            TextButton(onClick = onDismiss, enabled = !isImporting) { Text("返回") }
        }
    }
}

/** 导入选项卡片：色调图标容器 + 主副标题，M3 Expressive 大圆角 */
@Composable
private fun ImportOption(
    icon: String,
    title: String,
    subtitle: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (filled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (filled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(icon, style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (filled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
