package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutonomousBehaviorTest {

    private class FakeController : IAnimationStateController {
        override val currentState: StateFlow<PetAnimationState> get() = _state
        val _state = MutableStateFlow(PetAnimationState.IDLE)
        var lastSwitched: PetAnimationState? = null
        override fun switchState(st: PetAnimationState) { lastSwitched = st }
    }

    @Test
    fun `start and stop without crash`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val behavior = AutonomousBehavior(FakeController(), scope)
        behavior.start()
        behavior.stop()
    }

    @Test
    fun `stop without start does not crash`() = runTest {
        val behavior = AutonomousBehavior(FakeController(), TestScope(StandardTestDispatcher()))
        behavior.stop()
    }

    @Test
    fun `start after stop does not crash`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val behavior = AutonomousBehavior(FakeController(), scope)
        behavior.start()
        behavior.stop()
        behavior.start()
        behavior.stop()
    }

    @Test
    fun `onUserTouch prevents auto behavior within 5 seconds`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val controller = FakeController()
        controller._state.value = PetAnimationState.IDLE

        val behavior = AutonomousBehavior(controller, scope)
        behavior.start()
        behavior.onUserTouch()

        advanceTimeBy(15_000)
        behavior.stop()
        scope.testScheduler.advanceUntilIdle()

        assertNull("auto behavior should not fire right after touch", controller.lastSwitched)
    }

    @Test
    fun `does not switch when state is not IDLE`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val controller = FakeController()
        controller._state.value = PetAnimationState.RUNNING

        val behavior = AutonomousBehavior(controller, scope)
        behavior.start()

        advanceTimeBy(25_000)
        behavior.stop()
        scope.testScheduler.advanceUntilIdle()

        assertNull("should not switch when not IDLE", controller.lastSwitched)
    }

    @Test
    fun `stopped job does not trigger`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val controller = FakeController()
        controller._state.value = PetAnimationState.IDLE

        val behavior = AutonomousBehavior(controller, scope)
        behavior.start()
        behavior.stop()

        advanceTimeBy(25_000)
        scope.testScheduler.advanceUntilIdle()

        assertNull("stopped behavior should not trigger", controller.lastSwitched)
    }
}
