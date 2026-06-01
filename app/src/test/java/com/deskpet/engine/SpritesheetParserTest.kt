package com.deskpet.engine

import com.deskpet.data.model.AnimationState
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.PetAnimationState
import org.junit.Assert.assertEquals
import org.junit.Test

class SpritesheetParserTest {

    private val parser = SpritesheetParser()

    @Test
    fun `getFrameCount falls back to defaults when config has null states`() {
        val config = CodexPetConfig(
            id = "test",
            displayName = "Test",
            spritesheetPath = "/fake/test.webp",
            states = null
        )
        val frameCount = parser.getFrameCount(config, PetAnimationState.IDLE)
        assertEquals(6, frameCount)
    }

    @Test
    fun `getFrameCount uses custom state config when provided`() {
        val customState = AnimationState(name = "idle", row = 0, frames = 12)
        val config = CodexPetConfig(
            id = "test",
            displayName = "Test",
            spritesheetPath = "/fake/test.webp",
            states = listOf(customState)
        )
        val frameCount = parser.getFrameCount(config, PetAnimationState.IDLE)
        assertEquals(12, frameCount)
    }

    @Test
    fun `getFrameCount falls back for states not in custom config`() {
        val customState = AnimationState(name = "idle", row = 0, frames = 12)
        val config = CodexPetConfig(
            id = "test",
            displayName = "Test",
            spritesheetPath = "/fake/test.webp",
            states = listOf(customState)
        )
        val frameCount = parser.getFrameCount(config, PetAnimationState.RUNNING)
        assertEquals(6, frameCount)
    }

    @Test
    fun `getFrameCount returns default for all standard states with null states`() {
        val config = CodexPetConfig(
            id = "test",
            displayName = "Test",
            spritesheetPath = "/fake/test.webp",
            states = null
        )
        assertEquals(6, parser.getFrameCount(config, PetAnimationState.IDLE))
        assertEquals(8, parser.getFrameCount(config, PetAnimationState.RUNNING_RIGHT))
        assertEquals(8, parser.getFrameCount(config, PetAnimationState.RUNNING_LEFT))
        assertEquals(4, parser.getFrameCount(config, PetAnimationState.WAVING))
        assertEquals(5, parser.getFrameCount(config, PetAnimationState.JUMPING))
        assertEquals(8, parser.getFrameCount(config, PetAnimationState.FAILED))
        assertEquals(6, parser.getFrameCount(config, PetAnimationState.WAITING))
        assertEquals(6, parser.getFrameCount(config, PetAnimationState.RUNNING))
        assertEquals(6, parser.getFrameCount(config, PetAnimationState.REVIEW))
    }
}
