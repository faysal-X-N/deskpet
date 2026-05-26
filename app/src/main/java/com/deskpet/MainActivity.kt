package com.deskpet

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        val petList by repo.getAllPets().collectAsStateWithLifecycle(emptyList())
        val activeId by repo.getActivePetId().collectAsStateWithLifecycle(null)
        val isFirstLaunch by repo.isFirstLaunch().collectAsStateWithLifecycle(true)
        LaunchedEffect(Unit) { SamplePetLoader.loadSamplePets(this@MainActivity, repo) }

        if (isFirstLaunch) {
            AlertDialog(
                onDismissRequest = { sc.launch { repo.setFirstLaunchDone() } },
                title = { Text("欢迎使用桌面宠物") },
                text = { Text("宠物需要「悬浮窗」权限才能显示在其他 App 上方。\n\n操作说明：\n• 点击宠物 = 招手\n• 长按拖拽 = 移动\n• 双指缩放 = 调整大小") },
                confirmButton = {
                    TextButton(onClick = { sc.launch { repo.setFirstLaunchDone() } }) { Text("知道了") }
                }
            )
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
                        startService(Intent(this@MainActivity, PetOverlayService::class.java)
                            .apply { putExtra(PetOverlayService.EXTRA_PET_ID, it) })
                        overlayOn = true
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
                }
            )
        }
    }
}
