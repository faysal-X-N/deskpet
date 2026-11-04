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
import androidx.activity.enableEdgeToEdge
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
        // 全屏沉浸：内容延伸到状态栏后面（主题里只改图标明暗不够，
        // 必须在 DecorView 建立前调用，否则低版本系统仍是老式实色状态栏）
        enableEdgeToEdge()
        setContent { DeskPetTheme { App((application as DeskPetApp).petRepository) } }
    }

    private fun startOverlay(petId: String) {
        // 记录"用户想看宠物"的意图：进程被系统/ROM 杀掉后没有任何回调，
        // 下次打开 App 就靠这个标志自动恢复（主动关闭会写 false）
        (application as DeskPetApp).petRepository.setOverlayAutoResumeAsync(true)
        startService(
            Intent(this, PetOverlayService::class.java)
                .putExtra(PetOverlayService.EXTRA_PET_ID, petId)
        )
    }

    private fun stopOverlay() {
        // 用户主动关闭：清掉恢复意图，之后打开 App 不再自动拉起
        (application as DeskPetApp).petRepository.setOverlayAutoResumeAsync(false)
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
        val autoResume by repo.overlayAutoResume().collectAsStateWithLifecycle(false)
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

        // 自动恢复必须状态驱动、不能绑 ON_RESUME 事件：autoResume 标志从
        // DataStore 异步加载（初始 false），冷启动时 ON_RESUME 可能抢在
        // 值送达之前触发——表现为偶发"打开 App 宠物没回来，再切一次才回"。
        // 状态驱动则值一到、条件齐即恢复，与到达时机无关。
        // consumed 防重复：startService 后服务加载素材需要几百毫秒才报告
        // running=true，此窗口内 autoResume 已变 true 而条件仍满足，无保护
        // 会重复 startService 导致同宠物重载闪烁。标志变 false（主动关闭）
        // 时重置，下次开启仍可恢复。
        val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        var autoResumeConsumed by remember { mutableStateOf(false) }
        LaunchedEffect(
            lifecycleState, autoResume, overlayRunning, pendingStart, activeId
        ) {
            if (!autoResume) autoResumeConsumed = false
            if (!autoResumeConsumed && autoResume && !overlayRunning && !pendingStart &&
                lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
                Settings.canDrawOverlays(this@MainActivity)
            ) {
                activeId?.let {
                    autoResumeConsumed = true
                    startOverlay(it)
                }
            }
        }

        if (isFirstLaunch == true || showHelp) {
            WelcomeGuide {
                // 只有真正的首次启动弹窗才清除首次标记；从 ❓ 按钮打开的帮助不应碰这个状态
                if (isFirstLaunch == true) sc.launch {
                    // 首次标记写失败（磁盘满等）不能闪退，下次启动重弹欢迎页可接受
                    runCatching { repo.setFirstLaunchDone() }
                        .onFailure { Log.e("MainActivity", "setFirstLaunchDone failed", it) }
                }
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
                    // 写盘失败（存储异常/磁盘满）不闪退：UI 由数据流驱动，写失败列表不变
                    sc.launch { runCatching { repo.setActivePet(id) }
                        .onFailure { Log.e("MainActivity", "setActivePet failed", it) } }
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
                        try {
                            val pet = repo.getPet(id)
                            if (OverlayState.petId.value == id) stopOverlay()
                            repo.deletePet(id)
                            pet?.let { withContext(Dispatchers.IO) { FileManager(this@MainActivity).deletePet(it) } }
                        } catch (e: Exception) {
                            // 删除失败不闪退：列表不变、文件残留可下次再删
                            Log.e("MainActivity", "deletePet failed", e)
                            Toast.makeText(this@MainActivity, "删除失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onScaleChange = { s ->
                    activeId?.let {
                        sc.launch { runCatching { repo.setScale(it, s) }
                            .onFailure { e -> Log.e("MainActivity", "setScale failed", e) } }
                    }
                },
                onExportPet = { pet ->
                    sc.launch {
                        try {
                            val fm = FileManager(this@MainActivity)
                            val zip = withContext(Dispatchers.IO) { fm.exportPetToZip(pet) }
                            if (zip != null) fm.shareZip(zip)
                            else Toast.makeText(this@MainActivity, "导出失败", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            // 分享目标缺失（ActivityNotFoundException）等也不闪退
                            Log.e("MainActivity", "export failed", e)
                            Toast.makeText(this@MainActivity, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
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
                    Text("应用内置了 8 只示例宠物。你也可以导入更多：\n1. 点击底部的圆钮，展开控制面板\n2. 选择「导入」\n3. 选择「导入 Codex Pet 素材包」并选取下载的 .zip 文件")

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
                    Text("• 每只宠物的位置和大小独立记忆\n• 长按宠物栏卡片可删除宠物\n• 点开底部收纳坞可调大小、导入和备份\n• 划掉本应用不影响宠物显示，关闭请用通知栏或收纳坞\n• 换手机时用备份恢复功能迁移\n• 宠物不含网络权限，纯本地运行")

                    Spacer(Modifier.height(8.dp))

                    // 部分国产 ROM 会把前台服务一起清掉，给用户自救路径
                    Text("⚠️ 小米/OPPO/vivo 等机型注意", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Text("部分手机系统在「清理后台」时会连同宠物一起关闭（系统限制，所有悬浮窗应用都一样）。解决办法：\n1. 最近任务界面长按/下拉本应用卡片，点 🔒 锁定\n2. 设置 → 应用 → 桌面宠物 → 省电策略选「无限制」\n3. 允许「自启动」\n万一宠物被清掉，重新打开本应用会自动恢复。")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
        )
    }
}
