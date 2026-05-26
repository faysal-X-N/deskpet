package com.deskpet.overlay

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.deskpet.data.model.DragDirection
import com.deskpet.data.model.PetAnimationState
import kotlin.math.abs

class GestureHandler(
    private val onDrag: (dx: Float, dy: Float, direction: DragDirection) -> Unit,
    private val onTap: () -> Unit,
    private val isCodexPet: () -> Boolean,
    private val onScale: (Float) -> Unit = {}
) {
    val modifier: Modifier
        get() = Modifier
            .pointerInput("longpress_drag") {
                detectDragGesturesAfterLongPress(
                    onDragStart = { },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (isCodexPet()) {
                            val direction = when {
                                abs(dragAmount.x) > abs(dragAmount.y) && dragAmount.x > 0 -> DragDirection.RIGHT
                                abs(dragAmount.x) > abs(dragAmount.y) && dragAmount.x < 0 -> DragDirection.LEFT
                                dragAmount.y < 0 -> DragDirection.UP
                                else -> DragDirection.DOWN
                            }
                            onDrag(dragAmount.x, dragAmount.y, direction)
                        } else {
                            onDrag(dragAmount.x, dragAmount.y, DragDirection.RIGHT)
                        }
                    },
                    onDragEnd = { }
                )
            }
            .pointerInput("pinch") {
                awaitEachGesture {
                    var span = 0f
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.size >= 2) {
                            val p1 = active[0].position
                            val p2 = active[1].position
                            val newSpan = (p1 - p2).getDistance()
                            if (span > 0f && newSpan > 0f) onScale(newSpan / span)
                            span = newSpan
                            active.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput("tap") {
                detectTapGestures { onTap() }
            }

    fun mapDirectionToState(direction: DragDirection): PetAnimationState = when (direction) {
        DragDirection.LEFT -> PetAnimationState.RUNNING_LEFT
        DragDirection.RIGHT -> PetAnimationState.RUNNING_RIGHT
        DragDirection.UP -> PetAnimationState.JUMPING
        DragDirection.DOWN -> PetAnimationState.WAVING
    }
}
