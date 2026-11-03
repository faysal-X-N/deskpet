package com.deskpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskpet.data.FileManager
import com.deskpet.data.PetRepository
import com.deskpet.data.SamplePetLoader
import com.deskpet.overlay.OverlayState
import com.deskpet.overlay.PetOverlayService
import com.deskpet.ui.ImportScreen
import com.deskpet.ui.PetListScreen
import com.deskpet.ui.theme.DeskPetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeskPetTheme { App((application as DeskPetApp).petRepository) } }
    }

    private fun startOverlay(petId: String) {
        startService(
            Intent(this, PetOverlayService::class.java)
                .putExtra(PetOverlayService.EXTRA_PET_ID, petId)
        )
    }

    private fun stopOverlay() {
        stopService(Intent(this, PetOverlayService::class.java))
    }

    @Composable
    private fun App(repo: PetRepository) {
        val sc = rememberCoroutineScope()
        var showImport by remember { mutableStateOf(false) }
        var pendingStart by remember { mutableStateOf(false) }
        var showHelp by remember { mutableStateOf(false) }
        val petList by repo.getAllPets().collectAsStateWithLifecycle(emptyList())
        // 列表首帧未到时保持"加载中"，避免"还没有宠物"空状态在启动瞬间闪现
        val petListReady by remember { repo.getAllPets().map { true } }.collectAsStateWithLifecycle(false)
        val rawActiveId by repo.getActivePetId().collectAsStateWithLifecycle(null)
        // 删除宠物后仓库里会写入空串，统一当成"没有选中"
        val activeId = rawActiveId?.takeIf { it.isNotEmpty() }
        // 三态：null = 尚未读到 DataStore 的真实值。
        // 不能用 true 当初始值——磁盘读取需要几十毫秒，非首次启动时引导弹窗会
        // 先渲染几帧再消失，表现为每次冷启动"闪一下窗口"
        val isFirstLaunchFlow = remember { repo.isFirstLaunch().map { it as Boolean? } }
        val isFirstLaunch by isFirstLaunchFlow.collectAsStateWithLifecycle(null)
        val overlayRunning by OverlayState.running.collectAsStateWithLifecycle()
        // 缩放值以仓库中保存的值为准，否则重进应用/切换宠物后滑块位置会错
        val curScale = petList.find { it.id == activeId }?.scale ?: 1f
        LaunchedEffect(Unit) { SamplePetLoader.loadSamplePets(this@MainActivity, repo) }

        // Android 13+ 前台服务通知需要运行时权限，否则通知栏看不到"正在运行"
        val notifPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 拒绝也不影响悬浮窗，只是看不到常驻通知 */ }

        fun requestNotificationPermissionIfNeeded() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 从设置页返回后自动重试开启（用 rememberUpdatedState 读取最新值，
        // 否则 effect 只创建一次，捕获到的 activeId 还停留在首次组合时的 null）
        val latestActiveId by rememberUpdatedState(activeId)
        val latestPendingStart by rememberUpdatedState(pendingStart)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && latestPendingStart) {
                    // 无论授权与否都清掉待启动标记：
                    //  - 已授权 → 立即续上开启流程
                    //  - 未授权 → 用户放弃了这次操作，不清掉的话之后任何一次授权
                    //    （哪怕与本次操作无关）都会让宠物"凭空出现"
                    pendingStart = false
                    if (Settings.canDrawOverlays(this@MainActivity)) {
                        latestActiveId?.let {
                            requestNotificationPermissionIfNeeded()
                            startOverlay(it)
                        }
                    }
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        if (isFirstLaunch == true || showHelp) {
            WelcomeGuide {
                // 只有真正的首次启动弹窗才清除首次标记；从 ❓ 按钮打开的帮助不应碰这个状态
                if (isFirstLaunch == true) sc.launch { repo.setFirstLaunchDone() }
                showHelp = false
            }
        }

        if (showImport) {
            ImportScreen(repo, { showImport = false }, { showImport = false })
        } else {
            PetListScreen(
                petList = petList,
                isLoading = !petListReady,
                activePetId = activeId,
                isOverlayRunning = overlayRunning,
                curScale = curScale,
                onPetSelected = { id ->
                    sc.launch { repo.setActivePet(id) }
                    // 已经显示中就直接切换，不用先关再开
                    if (overlayRunning && OverlayState.petId.value != id) startOverlay(id)
                },
                onStartOverlay = {
                    activeId?.let {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            requestNotificationPermissionIfNeeded()
                            startOverlay(it)
                        } else {
                            pendingStart = true
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${this@MainActivity.packageName}".toUri())
                            startActivity(intent)
                        }
                    }
                },
                onStopOverlay = { stopOverlay() },
                onImportClick = { showImport = true },
                onDeletePet = { id ->
                    sc.launch {
                        val pet = repo.getPet(id)
                        if (OverlayState.petId.value == id) stopOverlay()
                        repo.deletePet(id)
                        pet?.let { withContext(Dispatchers.IO) { FileManager(this@MainActivity).deletePet(it) } }
                    }
                },
                onScaleChange = { s ->
                    activeId?.let { sc.launch { repo.setScale(it, s) } }
                },
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
                    Text("• 单击悬浮窗宠物 = 招手\n• 长按拖拽 = 移动位置\n• 拖拽方向 = 切换跑动/跳跃动画\n• 双指捏合 = 快速缩放\n• 界面滑块 = 精确控制大小")

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
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://codexpet.xyz/".toUri()))
                    })
                    Text(buildAnnotatedString {
                        append("👉 ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                            append("github.com/Codexdei/petdex")
                        }
                    }, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/Codexdei/petdex".toUri()))
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
