package com.deskpet.overlay

import com.deskpet.data.model.DragDirection
import com.deskpet.data.model.PetAnimationState
import org.junit.Assert.*
import org.junit.Test

class GestureHandlerTest {

    @Test
    fun `mapDirectionToState LEFT maps to RUNNING_LEFT`() {
        val handler = GestureHandler({ _, _, _ -> }, {}, { true })
        assertEquals(PetAnimationState.RUNNING_LEFT, handler.mapDirectionToState(DragDirection.LEFT))
    }

    @Test
    fun `mapDirectionToState RIGHT maps to RUNNING_RIGHT`() {
        val handler = GestureHandler({ _, _, _ -> }, {}, { true })
        assertEquals(PetAnimationState.RUNNING_RIGHT, handler.mapDirectionToState(DragDirection.RIGHT))
    }

    @Test
    fun `mapDirectionToState UP maps to JUMPING`() {
        val handler = GestureHandler({ _, _, _ -> }, {}, { true })
        assertEquals(PetAnimationState.JUMPING, handler.mapDirectionToState(DragDirection.UP))
    }

    @Test
    fun `mapDirectionToState DOWN maps to WAVING`() {
        val handler = GestureHandler({ _, _, _ -> }, {}, { true })
        assertEquals(PetAnimationState.WAVING, handler.mapDirectionToState(DragDirection.DOWN))
    }

    @Test
    fun `constructor does not crash with basic callbacks`() {
        val handler = GestureHandler(
            onDrag = { _, _, _ -> },
            onTap = { },
            isCodexPet = { false }
        )
        assertNotNull(handler)
    }

    @Test
    fun `modifier access does not crash`() {
        val handler = GestureHandler({ _, _, _ -> }, {}, { true })
        assertNotNull(handler.modifier)
    }
}
