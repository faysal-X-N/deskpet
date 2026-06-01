package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import kotlinx.coroutines.flow.StateFlow

interface IAnimationStateController {
    val currentState: StateFlow<PetAnimationState>
    fun switchState(st: PetAnimationState)
}
