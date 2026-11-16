package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState

/**
 * Codex Pet 的**逐帧停留时长表**（毫秒）。
 *
 * 这套数字不是本项目自创的：素材作者按它作画，规范里逐状态固定写死，
 * 例如待机一轮 = 280+110+110+140+140+320 = 1100ms，跑动 = 120×7+220 = 1060ms。
 *
 * 旧实现统一 500ms（待机）/ 200ms（其它）翻页，待机比规范慢 2.7 倍、
 * 跑动慢 1.5 倍，观感就是"幻灯片"。改回按表推进即可，不需要动解码。
 *
 * 注意：帧数是**权威**的（与 [PetAnimationState.DEFAULT_STATE_MAP] 一致）；
 * 若某个自制包在 pet.json 里声明了更多帧，超出部分沿用最后一帧的时长，
 * 声明更少则只用表的前 N 项——两种情况都不会越界。
 */
object PetFrameTiming {

    /** 表里没有的状态（未来新增/GIF 宠物）沿用的单帧时长。 */
    const val FALLBACK_FRAME_MS = 200

    private val TABLE: Map<PetAnimationState, IntArray> = mapOf(
        PetAnimationState.IDLE to intArrayOf(280, 110, 110, 140, 140, 320),
        PetAnimationState.RUNNING_RIGHT to intArrayOf(120, 120, 120, 120, 120, 120, 120, 220),
        PetAnimationState.RUNNING_LEFT to intArrayOf(120, 120, 120, 120, 120, 120, 120, 220),
        PetAnimationState.WAVING to intArrayOf(140, 140, 140, 280),
        PetAnimationState.JUMPING to intArrayOf(140, 140, 140, 140, 280),
        PetAnimationState.FAILED to intArrayOf(140, 140, 140, 140, 140, 140, 140, 240),
        PetAnimationState.WAITING to intArrayOf(150, 150, 150, 150, 150, 260),
        PetAnimationState.RUNNING to intArrayOf(120, 120, 120, 120, 120, 220),
        PetAnimationState.REVIEW to intArrayOf(150, 150, 150, 150, 150, 280)
    )

    /** 某状态第 [frameIndex] 帧应停留的毫秒数。越界索引钳到表的最后一帧。 */
    fun frameMs(state: PetAnimationState, frameIndex: Int): Int {
        val t = TABLE[state] ?: return FALLBACK_FRAME_MS
        if (t.isEmpty()) return FALLBACK_FRAME_MS
        return t[frameIndex.coerceIn(0, t.lastIndex)]
    }

    /** 该状态一轮动画的总时长（毫秒），用于测试与文档核对。 */
    fun cycleMs(state: PetAnimationState): Int = TABLE[state]?.sum() ?: FALLBACK_FRAME_MS

    /** 表里声明的帧数（0 表示该状态不在表内）。 */
    fun declaredFrames(state: PetAnimationState): Int = TABLE[state]?.size ?: 0
}
