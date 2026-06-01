package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnimationControllerTest {

    private fun createController() = AnimationController(
        PetEngine(SpritesheetParser(), GifRenderer()),
        TestScope(StandardTestDispatcher()).coroutineContext.let { kotlinx.coroutines.CoroutineScope(it) }
    )

    @Test
    fun `initial state is IDLE`() = runTest {
        val ctrl = createController()
        assertEquals(PetAnimationState.IDLE, ctrl.currentState.value)
    }

    @Test
    fun `initial frame is null`() = runTest {
        val ctrl = createController()
        assertNull(ctrl.currentFrame.value)
    }

    @Test
    fun `initial petWidth is 192`() = runTest {
        val ctrl = createController()
        assertEquals(192, ctrl.petWidth.value)
    }

    @Test
    fun `initial petHeight is 208`() = runTest {
        val ctrl = createController()
        assertEquals(208, ctrl.petHeight.value)
    }

    @Test
    fun `switchState changes currentState`() = runTest {
        val ctrl = createController()
        ctrl.switchState(PetAnimationState.WAVING)
        assertEquals(PetAnimationState.WAVING, ctrl.currentState.value)
    }

    @Test
    fun `switchState to RUNNING then back to IDLE`() = runTest {
        val ctrl = createController()
        ctrl.switchState(PetAnimationState.RUNNING)
        ctrl.switchState(PetAnimationState.IDLE)
        assertEquals(PetAnimationState.IDLE, ctrl.currentState.value)
    }

    @Test
    fun `reset returns to IDLE`() = runTest {
        val ctrl = createController()
        ctrl.switchState(PetAnimationState.JUMPING)
        ctrl.reset()
        assertEquals(PetAnimationState.IDLE, ctrl.currentState.value)
    }

    @Test
    fun `stop without start does not crash`() = runTest {
        val ctrl = createController()
        ctrl.stop()
    }

    @Test
    fun `setScale without loader does not crash`() = runTest {
        val ctrl = createController()
        ctrl.setScale(2f) // 没有 loader 时不改变尺寸，但不应崩溃
    }

    @Test
    fun `setScale with extreme values does not crash`() = runTest {
        val ctrl = createController()
        ctrl.setScale(0.1f)
        ctrl.setScale(10f)
        ctrl.setScale(3f)
    }

    @Test
    fun `isCodexPet returns false without loader`() = runTest {
        val ctrl = createController()
        assertFalse(ctrl.isCodexPet())
    }

    @Test
    fun `switchState to all 9 states`() = runTest {
        val ctrl = createController()
        PetAnimationState.entries.forEach { state ->
            ctrl.switchState(state)
            assertEquals(state, ctrl.currentState.value)
        }
    }
}
