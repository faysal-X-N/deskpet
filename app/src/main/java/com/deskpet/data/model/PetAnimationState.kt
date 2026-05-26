package com.deskpet.data.model

enum class PetAnimationState(val row: Int, val specName: String) {
    IDLE(0, "idle"),
    RUNNING_RIGHT(1, "running-right"),
    RUNNING_LEFT(2, "running-left"),
    WAVING(3, "waving"),
    JUMPING(4, "jumping"),
    FAILED(5, "failed"),
    WAITING(6, "waiting"),
    RUNNING(7, "running"),
    REVIEW(8, "review");

    companion object {
        /** 默认标准 9 行动画映射 */
        val DEFAULT_STATE_MAP: Map<String, AnimationState> = entries.associate { state ->
            state.specName to AnimationState(
                name = state.specName,
                row = state.row,
                frames = when (state) {
                    IDLE -> 6
                    RUNNING_RIGHT -> 8
                    RUNNING_LEFT -> 8
                    WAVING -> 4
                    JUMPING -> 5
                    FAILED -> 8
                    WAITING -> 6
                    RUNNING -> 6
                    REVIEW -> 6
                }
            )
        }

        fun fromSpecName(name: String): PetAnimationState? =
            entries.find { it.specName.equals(name, ignoreCase = true) }
    }
}
