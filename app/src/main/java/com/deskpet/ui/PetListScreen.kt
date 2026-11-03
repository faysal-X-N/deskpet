package com.deskpet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缩放交互（调研结论）：主流桌宠（VPet / Shimeji）都用"设置里滑块连续调节"，
 * 双指捏合负责现场快速缩放，滑块负责精确控制；UX 研究还指出需要一个"回到 1x"
 * 的快捷重置（相机 App 的 1x 按钮是使用率最高的档位）。因此：滑块 + 100% 按钮，
 * 淘汰点按跳档的按钮组（跳变不跟手、占两行、与捏合上限不一致）。
 *
 * 视觉（M3 规范）：滑块放进卡片容器、两端配加减号微调图标、轨道用 M3 Expressive
 * 风格的粗圆角轨道 + 自定义圆形拇指、100% 处放刻度点，百分比用胶囊标签展示。
 */
private const val MIN_SCALE_UI = 0.5f
private const val MAX_SCALE_UI = 5f
private const val SCALE_STEP = 0.1f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PetListScreen(
    petList: List<PetInfo>,
    isLoading: Boolean,
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
    onBackupAll: () -> Unit = {},
    onShowHelp: () -> Unit = {}
) {
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除宠物") },
            text = {
                Text("确定删除「${petList.find { it.id == id }?.displayName ?: "该宠物"}」？素材文件将被永久删除，无法恢复。")
            },
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
                IconButton(onClick = onImportClick) {
                    Icon(Icons.Default.Add, "导入", tint = MaterialTheme.colorScheme.onSurface)
                }
                if (activePetId != null) {
                    if (isOverlayRunning)
                        FilledTonalButton(
                            onClick = onStopOverlay,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("关闭")
                        }
                    else
                        Button(
                            onClick = onStartOverlay,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("开启")
                        }
                }
                IconButton(onClick = onShowHelp) {
                    Icon(Icons.Default.Info, "帮助", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

        // 列表数据未读出前不渲染滑块（activeId 可能先于列表到达，否则滑块先按 100% 渲染、
        // 数据到达后再跳到真实值，启动瞬间会跳一下）
        if (activePetId != null && !isLoading) {
            ScaleCard(curScale = curScale, onScaleChange = onScaleChange)
        }

        if (isLoading) {
            // 磁盘数据尚未读出：保持空白，避免"还没有宠物"空状态在启动瞬间闪现
        } else if (petList.isEmpty()) {
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
                items(petList, key = { it.id }, contentType = { it.type }) { pet ->
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

/**
 * 宠物大小调节卡片。
 *
 * 状态逻辑（勿改回旧写法）：value 始终用本地 sliderValue；repo 变化经 LaunchedEffect
 * 同步进来、拖动中忽略。不能用 shown = if(dragging) local else repo——松手瞬间 repo
 * 还是旧值（DataStore 异步写未完成），滑块会先弹回旧位置再跳回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaleCard(
    curScale: Float,
    onScaleChange: (Float) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(curScale) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(curScale) { if (!dragging) sliderValue = curScale }

    fun nudge(delta: Float) {
        val nv = (sliderValue + delta).coerceIn(MIN_SCALE_UI, MAX_SCALE_UI)
        if (nv != sliderValue) {
            sliderValue = nv
            onScaleChange(nv)
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // 标签行：说明文字 + 实时百分比胶囊 + 恢复原始大小
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "宠物大小",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        "${(sliderValue * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
                IconButton(
                    onClick = { sliderValue = 1f; onScaleChange(1f) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "恢复原始大小",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 调节行：[−] 滑块 [+]（M3：两端加减号辅助微调，触达性优于纯捏合）
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", "缩小") { nudge(-SCALE_STEP) }
                Slider(
                    value = sliderValue,
                    onValueChange = { dragging = true; sliderValue = it },
                    onValueChangeFinished = { dragging = false; onScaleChange(sliderValue) },
                    valueRange = MIN_SCALE_UI..MAX_SCALE_UI,
                    modifier = Modifier.weight(1f),
                    thumb = {
                        Box(
                            Modifier
                                .size(20.dp)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    },
                    track = { state ->
                        val fraction = ((state.value - state.valueRange.start) /
                            (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                        // 100% 刻度点在轨道上的相对位置
                        val resetFraction = (1f - MIN_SCALE_UI) / (MAX_SCALE_UI - MIN_SCALE_UI)
                        Box(
                            Modifier.fillMaxWidth().height(24.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // 静默轨道
                            Box(
                                Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            )
                            // 活跃轨道
                            Box(
                                Modifier.fillMaxWidth(fraction).height(8.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            // 100% 刻度点（被拇指经过时自然被覆盖）
                            Box(Modifier.fillMaxWidth(resetFraction)) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .size(4.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                    }
                )
                StepButton("+", "放大") { nudge(SCALE_STEP) }
            }
        }
    }
}

/** 滑块两端的加减号微调按钮 */
@Composable
private fun StepButton(symbol: String, desc: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    val scale by animateFloatAsState(
        if (isActive) 1.04f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 400f)
    )
    Card(
        modifier = Modifier.aspectRatio(1f).graphicsLayer {
            scaleX = scale; scaleY = scale
        }.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 6.dp else 1.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant
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
    val dir = java.io.File(pet.spritesheetPath).parentFile ?: return null
    val cache = dir.resolve("thumb_hq.png")
    if (cache.exists()) BitmapFactory.decodeFile(cache.absolutePath)?.let { return it }
    dir.listFiles { f -> f.name.startsWith("thumb") && f.name != "thumb_hq.png" }?.forEach { it.delete() }
    return try {
        val thumb = decodeFirstFrame(pet.spritesheetPath) ?: return null
        java.io.FileOutputStream(cache).use { o -> thumb.compress(Bitmap.CompressFormat.PNG, 80, o) }
        thumb
    } catch (e: Exception) {
        Log.w("PetListScreen", "thumbnail failed", e)
        null
    }
}

/**
 * 只解码精灵表的第一格（约 192x208 = 0.15MB）。
 * 旧实现用 BitmapFactory.decodeFile 解整张表（1536x1872 = 11MB/张），
 * 列表首屏多张卡片并发解码会直接 OOM。
 */
@Suppress("DEPRECATION")
private fun decodeFirstFrame(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val cw = bounds.outWidth / 8
    val ch = bounds.outHeight / 9
    if (cw <= 0 || ch <= 0) return null

    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    // API 28+ 支持 WebP 区域解码；更老的版本退回整表解码
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        var decoder: android.graphics.BitmapRegionDecoder? = null
        try {
            decoder = android.graphics.BitmapRegionDecoder.newInstance(path, false)
            decoder?.decodeRegion(android.graphics.Rect(0, 0, cw, ch), opts)?.let { return it }
        } catch (e: Exception) {
            Log.w("PetListScreen", "region decode failed, fallback to full decode", e)
        } finally {
            decoder?.recycle()
        }
    }

    val full = BitmapFactory.decodeFile(path, opts) ?: return null
    return try {
        Bitmap.createBitmap(full, 0, 0, cw, ch)
    } finally {
        full.recycle()
    }
}
