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
fun PetOverlayRenderer(anim: AnimationController, gestureModifier: Modifier, modifier: Modifier = Modifier) {
    val frame by anim.currentFrame.collectAsState()
    val w by anim.petWidth.collectAsState()
    val h by anim.petHeight.collectAsState()
    val d = LocalDensity.current
    Box(modifier.then(gestureModifier).size(with(d) { w.toDp() }, with(d) { h.toDp() }), Alignment.TopStart) {
        val img = remember(frame) { frame?.asImageBitmap() }
        Canvas(Modifier.fillMaxSize()) { img?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) } }
    }
}

