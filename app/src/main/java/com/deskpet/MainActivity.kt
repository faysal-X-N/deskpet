package com.deskpet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskpet.data.FileManager
import com.deskpet.data.PetRepository
import com.deskpet.data.SamplePetLoader
import com.deskpet.overlay.PetOverlayService
import com.deskpet.ui.ImportScreen
import com.deskpet.ui.PetListScreen
import com.deskpet.ui.theme.DeskPetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeskPetTheme { App((application as DeskPetApp).petRepository) } }
    }

    @Composable
    private fun App(repo: PetRepository) {
        val sc = rememberCoroutineScope()
        var showImport by remember { mutableStateOf(false) }
        var overlayOn by remember { mutableStateOf(false) }
        var scale by remember { mutableFloatStateOf(1f) }
        var pendingStart by remember { mutableStateOf(false) }
        var showHelp by remember { mutableStateOf(false) }
        val petList by repo.getAllPets().collectAsStateWithLifecycle(emptyList())
        val activeId by repo.getActivePetId().collectAsStateWithLifecycle(null)
        val isFirstLaunch by repo.isFirstLaunch().collectAsStateWithLifecycle(true)
        LaunchedEffect(Unit) { SamplePetLoader.loadSamplePets(this@MainActivity, repo) }

        // 从设置页返回后自动重试开启
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && pendingStart && Settings.canDrawOverlays(this@MainActivity)) {
                    pendingStart = false
                    activeId?.let {
                        startService(Intent(this@MainActivity, PetOverlayService::class.java)
                            .apply { putExtra(PetOverlayService.EXTRA_PET_ID, it) })
                        overlayOn = true
                    }
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        if (isFirstLaunch || showHelp) {
            WelcomeGuide { sc.launch { repo.setFirstLaunchDone(); showHelp = false } }
        }

        if (showImport) {
            ImportScreen(repo, { showImport = false }, { showImport = false })
        } else {
            PetListScreen(
                petList = petList,
                activePetId = activeId,
                isOverlayRunning = overlayOn,
                curScale = scale,
                onPetSelected = { sc.launch { repo.setActivePet(it) } },
                onStartOverlay = {
                    activeId?.let {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            startService(Intent(this@MainActivity, PetOverlayService::class.java)
                                .apply { putExtra(PetOverlayService.EXTRA_PET_ID, it) })
                            overlayOn = true
                        } else {
                            pendingStart = true
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${this@MainActivity.packageName}"))
                            startActivity(intent)
                        }
                    }
                },
                onStopOverlay = {
                    stopService(Intent(this@MainActivity, PetOverlayService::class.java))
                    overlayOn = false
                },
                onImportClick = { showImport = true },
                onDeletePet = { id ->
                    sc.launch {
                        val pet = repo.getPet(id)
                        repo.deletePet(id)
                        pet?.let { FileManager(this@MainActivity).deletePet(it) }
                    }
                },
                onScaleChange = { s -> scale = s; activeId?.let { sc.launch { repo.setScale(it, s) } } },
                onExportPet = { pet ->
                    sc.launch {
                        val fm = FileManager(this@MainActivity)
                        val zip = withContext(Dispatchers.IO) { fm.exportPetToZip(pet) }
                        if (zip != null) fm.shareZip(zip)
                        else Toast.makeText(this@MainActivity, "导出失败", Toast.LENGTH_SHORT).show()
                    }
                },
                onBackupAll = {
                    sc.launch {
                        try {
                            val fm = FileManager(this@MainActivity)
                            val zip = withContext(Dispatchers.IO) { fm.exportAllToZip(petList) }
                            if (zip != null) fm.shareZip(zip)
                            else Toast.makeText(this@MainActivity, "备份失败", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Backup crash", e)
                            Toast.makeText(this@MainActivity, "出错：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onShowHelp = { showHelp = true }
            )
        }
    }

    @Composable
    private fun WelcomeGuide(onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("🐾 欢迎使用桌面宠物", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 权限
                    Text("📌 权限说明", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("点击「开启」后如提示需悬浮窗权限，请允许。各手机路径不同，通常：设置 → 应用 → 桌面宠物 → 显示在其他应用上层")

                    Spacer(Modifier.height(12.dp))

                    // 操作
                    Text("🖐 操作方式", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("• 单击悬浮窗宠物 = 招手\n• 长按拖拽 = 移动位置\n• 双指捏合 = 缩放大小\n• 拖拽方向 = 切换跑动/跳跃动画\n• 界面缩放滑块 = 精确控制大小")

                    Spacer(Modifier.height(12.dp))

                    // 导入
                    Text("📦 导入 Codex 宠物", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("应用内置了 7 只示例宠物。你也可以导入更多：\n1. 点击底部「导入」按钮\n2. 选择「🎨 导入 Codex Pet 素材包」\n3. 选取下载的 .zip 文件")

                    Spacer(Modifier.height(8.dp))
                    Text("从哪里下载 Codex 宠物？", fontWeight = FontWeight.Bold)
                    Text(buildAnnotatedString {
                        append("👉 ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                            append("codexpet.xyz")
                        }
                    }, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://codexpet.xyz/")))
                    })
                    Text(buildAnnotatedString {
                        append("👉 ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                            append("github.com/Codexdei/petdex")
                        }
                    }, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Codexdei/petdex")))
                    })

                    Spacer(Modifier.height(12.dp))

                    // 提示
                    Text("💡 小提示", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("• 每只宠物的位置和大小独立记忆\n• 长按宠物栏卡片可删除宠物\n• 右上角菜单可备份全部宠物\n• 换手机时用备份恢复功能迁移\n• 宠物不含网络权限，纯本地运行")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
        )
    }
}
