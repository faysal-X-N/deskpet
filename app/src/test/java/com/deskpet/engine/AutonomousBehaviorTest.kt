package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 虚拟时间的正确用法（历史踩坑两连）：
 * 1. `TestScope(StandardTestDispatcher())` 不传 scheduler 会**新建一个独立调度器**，
 *    `advanceTimeBy` 只推进测试自己的那个 —— 被测协程永远停在起点，"不会触发"的
 *    断言全部空转（实现怎么改都能过）。必须 `StandardTestDispatcher(testScheduler)`。
 * 2. 被测代码若用 `System.currentTimeMillis()` 判断"空闲 30 秒"，虚拟时间推进得
 *    再多也不生效。因此 [AutonomousBehavior] 的时间源是可注入的，这里传
 *    `testScheduler.currentTime`，等待 30 秒这件事变成零耗时且确定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutonomousBehaviorTest {

    private class FakeController : IAnimationStateController {
        override val currentState: StateFlow<PetAnimationState> get() = _state
        val _state = MutableStateFlow(PetAnimationState.IDLE)
        var lastSwitched: PetAnimationState? = null
        override fun switchState(st: PetAnimationState) { lastSwitched = st }
    }

    /** 共享测试调度器 + 共享虚拟时钟 */
    private fun TestScope.newBehavior(controller: IAnimationStateController): AutonomousBehavior {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        return AutonomousBehavior(controller, scope) { testScheduler.currentTime }
    }

    @Test
    fun `start and stop without crash`() = runTest {
        val behavior = newBehavior(FakeController())
        behavior.start()
        behavior.stop()
    }

    @Test
    fun `stop without start does not crash`() = runTest {
        newBehavior(FakeController()).stop()
    }

    @Test
    fun `start after stop does not crash`() = runTest {
        val behavior = newBehavior(FakeController())
        behavior.start()
        behavior.stop()
        behavior.start()
        behavior.stop()
    }

    @Test
    fun `never switches before the shortest random interval`() = runTest {
        val controller = FakeController()
        val behavior = newBehavior(controller)
        behavior.start()

        // 最短随机间隔 5s（Random.nextLong(5_000, 30_001)），不到 5s 一拍都跑不到
        advanceTimeBy(4_900)
        behavior.stop()
        assertNull("4.9s 内不应有任何自动行为", controller.lastSwitched)
    }

    @Test
    fun `fires once the pet has been idle past the threshold`() = runTest {
        val controller = FakeController()
        val behavior = newBehavior(controller)
        behavior.start()

        // 61s 覆盖两轮随机间隔：首轮（≤30s）被"30s 无触摸"门槛挡下，
        // 第二轮（累计 ≤60s）必然越过门槛并切换状态
        advanceTimeBy(61_000)
        behavior.stop()
        assertNotNull("空闲超过 30s 后必须自己动起来", controller.lastSwitched)
        assertTrue(controller.lastSwitched in PetAnimationState.entries)
    }

    @Test
    fun `does not switch when state is not IDLE`() = runTest {
        val controller = FakeController()
        controller._state.value = PetAnimationState.RUNNING
        val behavior = newBehavior(controller)
        behavior.start()

        advanceTimeBy(61_000)
        behavior.stop()
        assertNull("非 IDLE 状态（用户正在拖拽/招手）不应被打断", controller.lastSwitched)
    }

    @Test
    fun `stopped job does not trigger`() = runTest {
        val controller = FakeController()
        val behavior = newBehavior(controller)
        behavior.start()
        behavior.stop()

        advanceTimeBy(61_000)
        assertNull("停止后不应再触发", controller.lastSwitched)
    }

    @Test
    fun `user touch postpones the next behavior`() = runTest {
        val controller = FakeController()
        val behavior = newBehavior(controller)
        behavior.start()

        // t=29s：无论首轮在 5~30s 哪一刻触发，都还没跨过"30s 无触摸"门槛
        advanceTimeBy(29_000)
        behavior.onUserTouch()   // 触摸把空闲计时重置到 t=29s
        // t=54s：距最近一次触摸只有 25s，这段窗口内所有触发都必须被门槛挡住
        advanceTimeBy(25_000)
        behavior.stop()
        assertNull("30s 内有触摸时不应自动切换", controller.lastSwitched)
    }
}
