package com.deskpet.overlay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.deskpet.data.model.DragDirection
import com.deskpet.data.model.PetAnimationState
import kotlin.math.abs

class GestureHandler(
    private val onDrag: (dx: Float, dy: Float, direction: DragDirection) -> Unit,
    private val onTap: () -> Unit,
    private val isCodexPet: () -> Boolean,
    private val onScale: (zoom: Float, focusX: Float, focusY: Float) -> Unit = { _, _, _ -> },
    private val onDragEnd: () -> Unit = {}
) {

    /**
     * 双指捏合进行中。长按拖拽回调据此让位——第二根手指落下后
     * 拖拽立即停止，避免"一边被拖一边缩放"的混乱手感。
     */
    @Volatile
    var pinching = false
        private set

    val modifier: Modifier
        get() = Modifier
            .pointerInput("longpress_drag") {
                detectDragGesturesAfterLongPress(
                    onDragStart = { },
                    onDrag = { change, dragAmount ->
                        // 捏合已接管：忽略本次拖拽（但仍消费事件防止泄漏给下层）
                        if (pinching) {
                            change.consume()
                            return@detectDragGesturesAfterLongPress
                        }
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
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
            .pointerInput("pinch") {
                awaitEachGesture {
                    var scaled = false
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            pinching = true
                            // calculateZoom 由 Compose 官方实现：基于 pressed 指针集合的
                            // 距离比，对第三指按下/某指抬起等指针增减场景是连续的，
                            // 不存在"下标取手指导致配对换人"的间距突变问题。
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                onScale(zoom, centroid.x, centroid.y)
                                scaled = true
                            }
                            // 消费全部按下指针，防止 tap 检测器把松开当点击
                            event.changes.forEach { it.consume() }
                        } else if (scaled) {
                            // 缩放已经发生后，剩余的单指事件继续消费到手势结束，
                            // 否则轻捏（移动少）松手时 tap 检测器会误触发一次"点击招手"
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scaled) onDragEnd()
                    // 无条件复位：即使两指接触但从未产生缩放，也要把标志还回去，
                    // 否则拖拽会被永久禁用
                    pinching = false
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
