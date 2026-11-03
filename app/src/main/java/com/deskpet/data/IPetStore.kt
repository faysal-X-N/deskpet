package com.deskpet.data

import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.flow.Flow

interface IPetStore {
    val petListFlow: Flow<List<PetInfo>>
    val activePetIdFlow: Flow<String?>
    val isFirstLaunch: Flow<Boolean>
    val samplesLoaded: Flow<Boolean>
    /** 用户是否希望宠物显示中（区别于"被系统杀掉"）：打开 App 时据此自动恢复 */
    val overlayAutoResumeFlow: Flow<Boolean>
    suspend fun updatePetList(list: List<PetInfo>)
    suspend fun setActivePetId(id: String)
    suspend fun setFirstLaunchDone()
    suspend fun setSamplesLoaded()
    suspend fun setOverlayAutoResume(v: Boolean)
}
