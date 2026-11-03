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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deskpet.data.FileManager
import com.deskpet.data.PetRepository
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     */
    fun runImport(uri: Uri, importer: suspend (Uri) -> PetInfo) {
        scope.launch {
            isImporting = true
            try {
                // 解压 / 拷贝文件是磁盘 IO，必须离开主线程
                val imported = withContext(Dispatchers.IO) { importer(uri) }
                if (petRepository.existsBySourceId(imported.sourceId)) {
                    // 文件已经解压到磁盘了，重复导入要把它们清掉，避免残留
                    withContext(Dispatchers.IO) { fileManager.deletePet(imported) }
                    snackbarHostState.showSnackbar("该宠物已存在")
                    return@launch
                }
                val added = petRepository.addPet(imported)
                petRepository.setActivePet(added.id)
                snackbarHostState.showSnackbar("导入成功：${added.displayName}")
                onImportSuccess()
            } catch (e: ImportException) {
                snackbarHostState.showSnackbar(e.message ?: "导入失败")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("导入失败：${e.message}")
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
                    withContext(Dispatchers.IO) { fileManager.restoreFromBackup(it, petRepository) }
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
            Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("导入宠物", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "支持 Codex Pet 标准素材包（.zip 含 pet.json）\n以及 GIF 动图文件",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { codexPetLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isImporting
            ) { Text("🎨 导入 Codex Pet 素材包") }
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { gifLauncher.launch(arrayOf("image/gif")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isImporting
            ) { Text("📷 导入 GIF 动图") }
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { backupLauncher.launch(arrayOf("application/zip")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isImporting
            ) { Text("💾 恢复备份") }

            if (isImporting) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onDismiss) { Text("返回") }
        }
    }
}
