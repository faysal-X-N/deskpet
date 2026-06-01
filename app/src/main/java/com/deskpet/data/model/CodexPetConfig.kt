package com.deskpet.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class CodexPetConfig(
    val id: String,
    val displayName: String,
    val description: String = "",
    val spritesheetPath: String,
    val schema_version: String? = null,
    val states: List<AnimationState>? = null
)

@Serializable
data class AnimationState(
    val name: String,
    val row: Int,
    val frames: Int
)
