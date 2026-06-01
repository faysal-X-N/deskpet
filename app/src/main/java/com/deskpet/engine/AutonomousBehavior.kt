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
                delay(Random.nextLong(8_000, 20_001))  // 8-20s
                if (System.currentTimeMillis() - lastTouchTime >= 5_000) {  // 5秒无触摸
                    val state = autoStates.random()
                    if (animationController.currentState.value == PetAnimationState.IDLE) {
                        animationController.switchState(state)
                    }
                }
            }
        }
    }

    fun stop() {
        behaviorJob?.cancel()
        behaviorJob = null
    }
}
