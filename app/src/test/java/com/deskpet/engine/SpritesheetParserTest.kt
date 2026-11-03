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
        val customState = AnimationState(name = "idle", row = 0, frames = 7)
        val config = CodexPetConfig(
            id = "test",
            displayName = "Test",
            spritesheetPath = "/fake/test.webp",
            states = listOf(customState)
        )
        val frameCount = parser.getFrameCount(config, PetAnimationState.IDLE)
        assertEquals(7, frameCount)
    }

    @Test
    fun `getFrameCount clamps declared frames to the 8-column grid width`() {
        // 恶意/损坏 pet.json 声明 50 帧：一行最多 8 列，钳到 8 防列号越界
        val customState = AnimationState(name = "idle", row = 0, frames = 50)
        val config = CodexPetConfig(
            id = "test",
            displayName = "Test",
            spritesheetPath = "/fake/test.webp",
            states = listOf(customState)
        )
        assertEquals(8, parser.getFrameCount(config, PetAnimationState.IDLE))
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

class SpritesheetParserFrameSizeTest {

    @Test
    fun `renderCellSize keeps normal sheets untouched`() {
        // 标准 1536x1872 素材：单元格 192x208，远小于上限，不降采样
        assertEquals(Pair(192, 208), SpritesheetParser.renderCellSize(1536, 1872))
    }

    @Test
    fun `renderCellSize downsamples giant single frames`() {
        // 16384x18432 病态大图：单元格 2048x2048 超过 1024 上限 → 2 倍降采样
        assertEquals(Pair(1024, 1024), SpritesheetParser.renderCellSize(16384, 18432))
    }

    @Test
    fun `renderCellSize handles extreme sheets without overflow`() {
        // 极端：40000x45000（平坦色 PNG 可压到极小）→ 单元格 5000x5000 → 8 倍采样
        val (w, h) = SpritesheetParser.renderCellSize(40000, 45000)
        assertEquals(625, w)
        assertEquals(625, h)
    }

    @Test
    fun `frameSampleSize is always a power of two and caps dimension`() {
        assertEquals(1, SpritesheetParser.frameSampleSize(192, 208))
        assertEquals(1, SpritesheetParser.frameSampleSize(1024, 500))
        assertEquals(2, SpritesheetParser.frameSampleSize(1025, 500))
        assertEquals(4, SpritesheetParser.frameSampleSize(4096, 4096))
        // ceil 语义：降采样后必然 ≤ 上限
        for (w in intArrayOf(1025, 1500, 2047, 2049)) {
            val s = SpritesheetParser.frameSampleSize(w, 100)
            assert((w + s - 1) / s <= SpritesheetParser.MAX_FRAME_DIM)
        }
    }
}
