package com.deskpet.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PetInfo(
    val id: String,
    val type: PetType,
    val displayName: String,
    val petJsonPath: String? = null,
    val spritesheetPath: String,
    val positionX: Float = 0f,
    val positionY: Float = 200f,
    val scale: Float = 1.0f,
    val isActive: Boolean = false,
    val sourceId: String = ""  // 去重标识：CodexPet=config.id, GIF=文件名
)
