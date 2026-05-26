package com.deskpet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val SCALE_OPTIONS = listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PetListScreen(
    petList: List<PetInfo>,
    activePetId: String?,
    isOverlayRunning: Boolean,
    curScale: Float,
    onPetSelected: (String) -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onImportClick: () -> Unit,
    onDeletePet: (String) -> Unit,
    onScaleChange: (Float) -> Unit,
    onExportPet: (PetInfo) -> Unit = {},
    onBackupAll: () -> Unit = {}
) {
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除宠物") },
            text = { Text("确定删除？") },
            confirmButton = {
                TextButton({ onDeletePet(id); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("取消") } }
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("宠物栏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onImportClick) { Text("导入") }
                if (activePetId != null) {
                    if (isOverlayRunning)
                        Button(onClick = onStopOverlay, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("关闭") }
                    else
                        Button(onClick = onStartOverlay) { Text("开启") }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("💾 备份全部") },
                            onClick = { menuExpanded = false; onBackupAll() },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (activePetId != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SCALE_OPTIONS.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { s ->
                            FilterChip(
                                selected = curScale == s,
                                onClick = { onScaleChange(s) },
                                label = { Text("${(s * 100).toInt()}%", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size < 3) repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (petList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有宠物", style = MaterialTheme.typography.bodyLarge)
                    Text("点击「导入」添加\n长按宠物可删除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(petList, key = { it.id }) { pet ->
                    PetGridCard(
                        pet = pet,
                        isActive = pet.id == activePetId,
                        onClick = { onPetSelected(pet.id) },
                        onLongClick = { deleteTarget = pet.id },
                        onExport = { onExportPet(pet) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PetGridCard(
    pet: PetInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onExport: () -> Unit = {}
) {
    Card(
        modifier = Modifier.aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 6.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isActive) 3.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                PetPreview(pet)
                Spacer(Modifier.height(2.dp))
                Text(pet.displayName, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            if (isActive) {
                Icon(Icons.Default.CheckCircle, "当前宠物",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
            }
            IconButton(onClick = onExport, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)) {
                Icon(Icons.Default.Share, "导出",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PetPreview(pet: PetInfo) {
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pet.spritesheetPath) {
        if (pet.type == PetType.CODEX_PET) thumb = withContext(Dispatchers.IO) { loadOrCreateThumb(pet) }
    }
    if (thumb != null) Image(thumb!!.asImageBitmap(), pet.displayName, Modifier.fillMaxSize(0.75f), contentScale = ContentScale.Fit)
    else Text(if (pet.type == PetType.CODEX_PET) "🎨" else "📷", style = MaterialTheme.typography.headlineMedium)
}

private fun loadOrCreateThumb(pet: PetInfo): Bitmap? {
    val cache = java.io.File(pet.spritesheetPath).parentFile?.resolve("thumb_hq.png") ?: return null
    if (cache.exists()) return BitmapFactory.decodeFile(cache.absolutePath)
    java.io.File(pet.spritesheetPath).parentFile?.listFiles { f -> f.name.startsWith("thumb") && f.name != "thumb_hq.png" }?.forEach { it.delete() }
    return try {
        val full = BitmapFactory.decodeFile(pet.spritesheetPath) ?: return null
        val cw = full.width / 8; val ch = full.height / 9
        val t = if (cw > 0 && ch > 0) Bitmap.createBitmap(full, 0, 0, cw.coerceAtMost(full.width), ch.coerceAtMost(full.height)) else null
        full.recycle()
        t?.let { java.io.FileOutputStream(cache).use { o -> it.compress(Bitmap.CompressFormat.PNG, 80, o) } }; t
    } catch (e: Exception) { Log.w("PetListScreen", "thumbnail failed", e); null }
}
