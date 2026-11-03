package com.deskpet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
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

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
            // 顶栏（M3 规范："app bar 最多 1-2 个操作，能不用溢出菜单就不用，
            // 操作多就放进 toolbar"）：本页所有操作都在底部收纳坞，顶栏零图标
            Column(Modifier.weight(1f, fill = false)) {
                Text("宠物栏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (!isLoading && petList.isNotEmpty()) {
                    Text(
                        "${petList.size} 只宠物 · " + if (isOverlayRunning) "运行中" else "未开启",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

        if (isLoading) {
            // 磁盘数据尚未读出：保持空白，避免"还没有宠物"空状态在启动瞬间闪现
        } else if (petList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        Modifier.padding(horizontal = 36.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🐾", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(10.dp))
                        Text("还没有宠物", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "从本地素材包或 GIF 导入\n长按宠物可删除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onImportClick,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("导入宠物")
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                // 网格先避让导航条，再留收纳坞的高度（坞=12dp 边距+约 44dp 药丸，
                // 88dp 余量保证最后一行卡片在收纳态不被遮挡）
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding()
            ) {
                items(petList, key = { it.id }, contentType = { it.type }) { pet ->
                    PetGridCard(
                        pet = pet,
                        isActive = pet.id == activePetId,
                        modifier = Modifier.animateItem(),
                        onClick = { onPetSelected(pet.id) },
                        onLongClick = { deleteTarget = pet.id },
                        onExport = { onExportPet(pet) }
                    )
                }
            }
        }
        }

        // 底部收纳坞（FloatingToolbar 模式）：滑块与开关默认收进一条药丸，
        // 点按展开。次要操作（导入/帮助/备份）也住在坞里（M3：操作多就放
        // toolbar，别堆顶栏）。只要有宠物就显示；列表数据未读出前不渲染
        // （activeId 可能先于列表到达，否则滑块先按 100% 渲染、数据到达后
        // 再跳，启动瞬间会跳一下）
        if (petList.isNotEmpty() && !isLoading) {
            PetDock(
                isRunning = isOverlayRunning,
                canStart = activePetId != null,
                curScale = curScale,
                onStartOverlay = onStartOverlay,
                onStopOverlay = onStopOverlay,
                onScaleChange = onScaleChange,
                onImportClick = onImportClick,
                onShowHelp = onShowHelp,
                onBackupAll = onBackupAll,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * 底部收纳坞（调研结论）：M3 Expressive 的 FloatingToolbar 是 2026 官方推荐的
 * "多操作收纳"组件——默认收成一条药丸、点按展开；其底层就是 AnimatedVisibility
 * + 弹簧动画这套原语。本实现复刻同款交互而不引入 material3 1.5 的整链升级：
 * 收纳态是一条状态药丸（运行点 + 百分比 + 旋转箭头），展开后露出滑块行与
 * 全宽开关按钮。注：容器高度动画交给 expand/shrinkVertically，不再叠加
 * animateContentSize——两套高度动画叠加会互相抖动。
 *
 * 滑块状态逻辑（勿改回旧写法）：value 始终用本地 sliderValue；repo 变化经
 * LaunchedEffect 同步进来、拖动中忽略。不能用 shown = if(dragging) local else
 * repo——松手瞬间 repo 还是旧值（DataStore 异步写未完成），滑块会先弹回旧位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PetDock(
    isRunning: Boolean,
    canStart: Boolean,
    curScale: Float,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onScaleChange: (Float) -> Unit,
    onImportClick: () -> Unit = {},
    onShowHelp: () -> Unit = {},
    onBackupAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
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

    // 展开/收起、箭头旋转、圆角形变共用同一 M3 Expressive 弹簧节奏
    val motion = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, motion)
    val cornerRadius by animateDpAsState(
        if (expanded) 24.dp else 28.dp,
        spring(dampingRatio = 0.8f, stiffness = 380f)
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp
    ) {
        Column {
            // —— 收纳态表头（常显）：点按展开/收起 ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (isRunning) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) "运行中" else "未开启",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(sliderValue * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = if (expanded) "收起控制" else "展开控制",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                )
            }

            // —— 展开内容：滑块行 + 全宽开关按钮 ——
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(motion) + expandVertically(
                    spring(dampingRatio = 0.8f, stiffness = 380f)
                ),
                exit = fadeOut(motion) + shrinkVertically(
                    spring(dampingRatio = 0.8f, stiffness = 380f)
                )
            ) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "宠物大小",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
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
                                    Box(
                                        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                    )
                                    Box(
                                        Modifier.fillMaxWidth(fraction).height(8.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
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
                    Spacer(Modifier.height(10.dp))
                    if (isRunning) {
                        FilledTonalButton(
                            onClick = onStopOverlay,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("关闭宠物")
                        }
                    } else {
                        Button(
                            onClick = onStartOverlay,
                            enabled = canStart,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (canStart) "开启宠物" else "先选择一只宠物")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 次要操作行（M3：低频操作放 toolbar，安静的文字按钮）
                    Row(Modifier.fillMaxWidth()) {
                        DockAction(Icons.Default.Add, "导入") { onImportClick() }
                        DockAction(Icons.Default.Info, "帮助") { onShowHelp() }
                        DockAction(Icons.Default.Share, "备份全部") { onBackupAll() }
                    }
                }
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

/** 收纳坞展开面板里的安静文字按钮（次要操作：导入/帮助/备份） */
@Composable
private fun RowScope.DockAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(icon, null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PetGridCard(
    pet: PetInfo,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onExport: () -> Unit = {}
) {
    val scale by animateFloatAsState(
        if (isActive) 1.04f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 400f)
    )
    Card(
        modifier = modifier.aspectRatio(1f).graphicsLayer {
            scaleX = scale; scaleY = scale
        }.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        // M3 Expressive：深度用色调差而不是阴影，选中用 primaryContainer 实色
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = if (isActive) null
        else androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            if (isActive) {
                // 选中徽章：右上角小对勾，不依赖颜色单独传达状态（无障碍）
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check, "已选中",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PetPreview(pet)
                Spacer(Modifier.height(2.dp))
                Text(
                    pet.displayName, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)) {
                Icon(
                    Icons.Default.Share, "导出",
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
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
