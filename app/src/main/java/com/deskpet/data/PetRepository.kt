package com.deskpet.data

import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class PetRepository(private val petStore: IPetStore) {

    fun getAllPets(): Flow<List<PetInfo>> = petStore.petListFlow

    fun getActivePetId(): Flow<String?> = petStore.activePetIdFlow

    fun getActivePet(): Flow<PetInfo?> = combine(
        petStore.petListFlow,
        petStore.activePetIdFlow
    ) { pets, activeId ->
        pets.find { it.id == activeId }
    }

    suspend fun addPet(petInfo: PetInfo): PetInfo {
        val withId = petInfo.copy(id = UUID.randomUUID().toString())
        val currentList = petStore.petListFlow.first().toMutableList()
        currentList.add(withId)
        petStore.updatePetList(currentList)
        return withId
    }

    /** 直接添加宠物（保持原始 ID，不生成新 UUID）—— 用于恢复备份 */
    suspend fun addPetDirect(petInfo: PetInfo) {
        val currentList = petStore.petListFlow.first().toMutableList()
        currentList.add(petInfo)
        petStore.updatePetList(currentList)
    }

    suspend fun deletePet(petId: String) {
        val currentList = petStore.petListFlow.first().toMutableList()
        currentList.removeAll { it.id == petId }
        petStore.updatePetList(currentList)

        // 如果删除的是当前活跃宠物，清除活跃标记
        val activeId = petStore.activePetIdFlow.first()
        if (activeId == petId) {
            petStore.setActivePetId("")
        }
    }

    suspend fun setActivePet(petId: String) {
        val currentList = petStore.petListFlow.first().toMutableList()
        val updatedList = currentList.map { it.copy(isActive = it.id == petId) }
        petStore.updatePetList(updatedList)
        petStore.setActivePetId(petId)
    }

    suspend fun savePosition(petId: String, x: Float, y: Float, scale: Float) {
        val currentList = petStore.petListFlow.first().toMutableList()
        val index = currentList.indexOfFirst { it.id == petId }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(positionX = x, positionY = y, scale = scale)
            petStore.updatePetList(currentList)
        }
    }

    suspend fun setScale(petId: String, scale: Float) {
        val currentList = petStore.petListFlow.first().toMutableList()
        val index = currentList.indexOfFirst { it.id == petId }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(scale = scale)
            petStore.updatePetList(currentList)
        }
    }

    suspend fun getPet(petId: String): PetInfo? {
        return petStore.petListFlow.first().find { it.id == petId }
    }
    suspend fun existsByPath(path: String): Boolean = petStore.petListFlow.first().any { it.spritesheetPath == path }
    suspend fun existsBySourceId(sourceId: String): Boolean = sourceId.isNotEmpty() && petStore.petListFlow.first().any { it.sourceId == sourceId }
    fun isFirstLaunch(): Flow<Boolean> = petStore.isFirstLaunch

    suspend fun setFirstLaunchDone() {
        petStore.setFirstLaunchDone()
    }
}
