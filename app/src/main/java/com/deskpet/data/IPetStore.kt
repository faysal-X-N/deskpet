package com.deskpet.data

import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.flow.Flow

interface IPetStore {
    val petListFlow: Flow<List<PetInfo>>
    val activePetIdFlow: Flow<String?>
    val isFirstLaunch: Flow<Boolean>
    suspend fun updatePetList(list: List<PetInfo>)
    suspend fun setActivePetId(id: String)
    suspend fun setFirstLaunchDone()
}
