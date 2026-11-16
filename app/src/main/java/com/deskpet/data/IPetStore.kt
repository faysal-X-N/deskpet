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
    /** 幽灵模式（触摸穿透）：宠物可见可动，但手指穿过它直达下层应用 */
    val ghostModeFlow: Flow<Boolean>
    suspend fun updatePetList(list: List<PetInfo>)
    suspend fun setActivePetId(id: String)
    suspend fun setFirstLaunchDone()
    suspend fun setSamplesLoaded()
    suspend fun setOverlayAutoResume(v: Boolean)
    suspend fun setGhostMode(v: Boolean)
    /** 原子翻转幽灵模式：DataStore 的 edit 串行执行，快速连点 N 次必翻 N 次
     *  （读-改-写拆开的话，连点会读到同一个旧值，只翻一次） */
    suspend fun toggleGhostMode()
}
