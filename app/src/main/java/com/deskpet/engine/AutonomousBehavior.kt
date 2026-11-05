package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import kotlinx.coroutines.*
import kotlin.random.Random

class AutonomousBehavior(
    private val animationController: IAnimationStateController,
    private val coroutineScope: CoroutineScope,
    /**
     * 时间源。默认系统墙钟；单元测试注入虚拟时钟（TestScheduler.currentTime）。
     * 时间源被写死成 System.currentTimeMillis() 时，"空闲 30 秒"这条门槛在
     * 虚拟时间测试里永远不成立（delay 走虚拟时钟、时间戳走真实时钟），
     * 只能靠真实等待或干脆不测——这正是本类此前 4 个测试全是空转的原因。
     */
    private val now: () -> Long = System::currentTimeMillis
) {
    private val autoStates = listOf(
        PetAnimationState.RUNNING,
        PetAnimationState.JUMPING,
        PetAnimationState.WAITING,
        PetAnimationState.REVIEW
    )

    private var lastTouchTime: Long = now()
    private var behaviorJob: Job? = null

    fun onUserTouch() {
        lastTouchTime = now()
    }

    fun start() {
        behaviorJob?.cancel()
        behaviorJob = coroutineScope.launch {
            while (isActive) {
                delay(Random.nextLong(5_000, 30_001))  // 5~30 秒随机间隔
                // 30 秒内有过触摸就跳过，避免用户正在互动时宠物自己乱动
                if (now() - lastTouchTime >= IDLE_THRESHOLD_MS) {
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
