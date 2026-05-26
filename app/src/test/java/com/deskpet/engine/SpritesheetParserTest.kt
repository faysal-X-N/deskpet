package com.deskpet.engine

import android.graphics.Rect
import com.deskpet.data.model.AnimationState
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.PetAnimationState
import org.junit.Assert.assertEquals
import org.junit.Test

class SpritesheetParserTest {

    private val parser = SpritesheetParser()

    // ── calculateCellRect ──

    @Test
    fun `calculateCellRect returns correct rect for top-left cell`() {
        val rect = parser.calculateCellRect(row = 0, col = 0, w = 800, h = 900)

        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(100, rect.right)   // 800 / 8
        assertEquals(100, rect.bottom)  // 900 / 9
    }

    @Test
    fun `calculateCellRect returns correct rect for middle cell`() {
        val rect = parser.calculateCellRect(row = 1, col = 3, w = 800, h = 900)

        assertEquals(300, rect.left)    // 3 * 100
        assertEquals(100, rect.top)     // 1 * 100
        assertEquals(400, rect.right)   // 4 * 100
        assertEquals(200, rect.bottom)  // 2 * 100
    }

    @Test
    fun `calculateCellRect returns correct rect for last cell`() {
        val rect = parser.calculateCellRect(row = 8, col = 7, w = 800, h = 900)

        assertEquals(700, rect.left)    // 7 * 100
        assertEquals(800, rect.top)     // 8 * 100
        assertEquals(800, rect.right)   // 8 * 100
        assertEquals(900, rect.bottom)  // 9 * 100
    }

    @Test
    fun `calculateCellRect with non-even dimensions`() {
        val rect = parser.calculateCellRect(row = 0, col = 0, w = 801, h = 902)

        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(100, rect.right)   // 801 / 8 = 100 (int division)
        assertEquals(100, rect.bottom)  // 902 / 9 = 100 (int division)
    }

    // ── getStateConfig fallback (tested via getFrameCount) ──

    @Test
    fun `getFrameCount falls back to defaults when config has null states`() {
        val config = CodexPetConfig(
            id = "test",
            displayName = "Test",
            spritesheetPath = "/fake/test.webp",
            states = null  // null states → fallback to DEFAULT_STATE_MAP
        )

        // IDLE default frame count is 6
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
            states = listOf(customState)  // only idle defined, running uses default
        )

        // RUNNING is not in custom config → fallback to DEFAULT_STATE_MAP value (6)
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

        // verify each standard state gets correct default frame count
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
