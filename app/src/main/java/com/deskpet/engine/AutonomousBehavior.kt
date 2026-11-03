package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import kotlinx.coroutines.*
import kotlin.random.Random

class AutonomousBehavior(
    private val animationController: IAnimationStateController,
    private val coroutineScope: CoroutineScope
) {
    private val autoStates = listOf(
        PetAnimationState.RUNNING,
        PetAnimationState.JUMPING,
        PetAnimationState.WAITING,
        PetAnimationState.REVIEW
    )

    private var lastTouchTime: Long = System.currentTimeMillis()
    private var behaviorJob: Job? = null

    fun onUserTouch() {
        lastTouchTime = System.currentTimeMillis()
    }

    fun start() {
        behaviorJob?.cancel()
        behaviorJob = coroutineScope.launch {
            while (isActive) {
                delay(Random.nextLong(5_000, 30_001))  // 5~30 秒随机间隔
                // 30 秒内有过触摸就跳过，避免用户正在互动时宠物自己乱动
                if (System.currentTimeMillis() - lastTouchTime >= IDLE_THRESHOLD_MS) {
                    if (animationController.currentState.value == PetAnimationState.IDLE) {
                        animationController.switchState(autoStates.random())
                    }
                }
            }
        }
    }

    fun stop() {
        behaviorJob?.cancel()
        behaviorJob = null
    }

    companion object {
        private const val IDLE_THRESHOLD_MS = 30_000L
    }
}
