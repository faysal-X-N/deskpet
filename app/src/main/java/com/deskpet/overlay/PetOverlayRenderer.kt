package com.deskpet.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.deskpet.engine.AnimationController

@Composable
fun PetOverlayRenderer(
    anim: AnimationController,
    modifier: Modifier = Modifier
) {
    val frame by anim.currentFrame.collectAsState()
    val w by anim.petWidth.collectAsState()
    val h by anim.petHeight.collectAsState()
    val d = LocalDensity.current
    // asImageBitmap() 每次调用都会新建一层包装对象；这个组件 5FPS 重组，
    // 不 remember 就是每秒 5 个没必要的分配（像素数据不复制，但包装对象和
    // 绘制描述符会进垃圾堆）。按帧序号缓存，位图本身由引擎双缓冲复用。
    val image = remember(frame) { frame?.bitmap?.asImageBitmap() }
    Box(modifier.size(with(d) { w.toDp() }, with(d) { h.toDp() }), Alignment.TopStart) {
        Canvas(Modifier.fillMaxSize()) {
            image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
        }
    }
}
