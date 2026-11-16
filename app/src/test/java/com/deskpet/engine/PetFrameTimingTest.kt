package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codex 规范逐帧时长表的回归测试。
 *
 * 这张表是**素材作者作画时的节奏**，不是可以随手调的参数：待机一轮 1100ms、
 * 跑动 1060ms。旧实现统一 500/200ms，待机慢 2.7 倍，观感像幻灯片。
 * 任何"顺手把数字改大一点"的改动都会在这里红掉。
 */
class PetFrameTimingTest {

    @Test
    fun `idle cycle is 1100ms per spec`() {
        assertEquals(1100, PetFrameTiming.cycleMs(PetAnimationState.IDLE))
    }

    @Test
    fun `running cycle is 1060ms per spec`() {
        assertEquals(1060, PetFrameTiming.cycleMs(PetAnimationState.RUNNING_RIGHT))
        assertEquals(1060, PetFrameTiming.cycleMs(PetAnimationState.RUNNING_LEFT))
    }

    @Test
    fun `all nine states match spec cycle totals`() {
        val expected = mapOf(
            PetAnimationState.IDLE to 1100,
            PetAnimationState.RUNNING_RIGHT to 1060,
            PetAnimationState.RUNNING_LEFT to 1060,
            PetAnimationState.WAVING to 700,
            PetAnimationState.JUMPING to 840,
            PetAnimationState.FAILED to 1220,
            PetAnimationState.WAITING to 1010,
            PetAnimationState.RUNNING to 820,
            PetAnimationState.REVIEW to 1030
        )
        expected.forEach { (state, ms) ->
            assertEquals("cycle of ${state.specName}", ms, PetFrameTiming.cycleMs(state))
        }
    }

    @Test
    fun `declared frame counts match the 8-column atlas`() {
        PetAnimationState.entries.forEach { state ->
            val n = PetFrameTiming.declaredFrames(state)
            assert(n in 1..8) { "${state.specName} 声明了 $n 帧，超过一行 8 列" }
            // 与状态机默认帧数保持一致，否则时序表与真实帧数会错位
            assertEquals(
                "${state.specName} 帧数与 DEFAULT_STATE_MAP 不一致",
                PetAnimationState.DEFAULT_STATE_MAP.getValue(state.specName).frames,
                n
            )
        }
    }

    @Test
    fun `idle frame durations are non-uniform and end with the long beat`() {
        assertEquals(280, PetFrameTiming.frameMs(PetAnimationState.IDLE, 0))
        assertEquals(110, PetFrameTiming.frameMs(PetAnimationState.IDLE, 1))
        assertEquals(320, PetFrameTiming.frameMs(PetAnimationState.IDLE, 5))
    }

    @Test
    fun `out of range index clamps to last beat instead of crashing`() {
        assertEquals(320, PetFrameTiming.frameMs(PetAnimationState.IDLE, 99))
        assertEquals(280, PetFrameTiming.frameMs(PetAnimationState.IDLE, -3))
    }

    @Test
    fun `fallback is 200ms for gif pets`() {
        assertEquals(200, PetFrameTiming.FALLBACK_FRAME_MS)
    }
}
