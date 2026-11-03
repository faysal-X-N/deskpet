package com.deskpet.data

import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class PetRepository(private val petStore: IPetStore) {

    /**
     * DataStore 的单次 edit 是原子的，但这里都是「先读列表 → 改 → 再写」，
     * 并发时（例如示例宠物加载与用户导入同时发生）会丢更新，所以写入统一串行化。
     */
    private val writeMutex = Mutex()

    /**
     * 独立的持久化作用域：服务 onDestroy 时自己的 scope 已取消，
     * 数据落盘要走这个 scope 异步完成，不能用 runBlocking 阻塞主线程。
     */
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getAllPets(): Flow<List<PetInfo>> = petStore.petListFlow

    fun getActivePetId(): Flow<String?> = petStore.activePetIdFlow

    fun getActivePet(): Flow<PetInfo?> = combine(
        petStore.petListFlow,
        petStore.activePetIdFlow
    ) { pets, activeId ->
        pets.find { it.id == activeId }
    }

    suspend fun addPet(petInfo: PetInfo): PetInfo = writeMutex.withLock {
        val withId = petInfo.copy(id = UUID.randomUUID().toString())
        val currentList = petStore.petListFlow.first().toMutableList()
        currentList.add(withId)
        petStore.updatePetList(currentList)
        withId
    }

    /** 直接添加宠物（保持原始 ID，不生成新 UUID）—— 用于恢复备份 */
    suspend fun addPetDirect(petInfo: PetInfo) {
        writeMutex.withLock {
            val currentList = petStore.petListFlow.first().toMutableList()
            currentList.add(petInfo)
            petStore.updatePetList(currentList)
        }
    }

    suspend fun deletePet(petId: String) {
        writeMutex.withLock {
            val currentList = petStore.petListFlow.first().toMutableList()
            if (currentList.none { it.id == petId }) return@withLock
            currentList.removeAll { it.id == petId }
            petStore.updatePetList(currentList)

            // 如果删除的是当前活跃宠物，清除活跃标记
            val activeId = petStore.activePetIdFlow.first()
            if (activeId == petId) {
                petStore.setActivePetId("")
            }
        }
    }

    suspend fun setActivePet(petId: String) {
        writeMutex.withLock {
            val currentList = petStore.petListFlow.first()
            val updatedList = currentList.map { it.copy(isActive = it.id == petId) }
            petStore.updatePetList(updatedList)
            petStore.setActivePetId(petId)
        }
    }

    suspend fun savePosition(petId: String, x: Float, y: Float, scale: Float) {
        writeMutex.withLock {
            val currentList = petStore.petListFlow.first().toMutableList()
            val index = currentList.indexOfFirst { it.id == petId }
            if (index >= 0) {
                val old = currentList[index]
                if (old.positionX == x && old.positionY == y && old.scale == scale) return@withLock
                currentList[index] = old.copy(positionX = x, positionY = y, scale = scale)
                petStore.updatePetList(currentList)
            }
        }
    }

    /** 异步落盘（不阻塞调用方）：供服务销毁等无法挂起的场景使用 */
    fun savePositionAsync(petId: String, x: Float, y: Float, scale: Float) {
        persistenceScope.launch {
            runCatching { savePosition(petId, x, y, scale) }
                .onFailure { android.util.Log.w("PetRepository", "Async position save failed", it) }
        }
    }

    suspend fun setScale(petId: String, scale: Float) {
        // 存储层兜底夹取：与 UI 滑块 / 悬浮窗捏合的 0.5~5 保持一致，
        // 防止任何未来调用方写入范围外的脏值
        val scale = scale.coerceIn(0.5f, 5f)
        writeMutex.withLock {
            val currentList = petStore.petListFlow.first().toMutableList()
            val index = currentList.indexOfFirst { it.id == petId }
            if (index >= 0 && currentList[index].scale != scale) {
                currentList[index] = currentList[index].copy(scale = scale)
                petStore.updatePetList(currentList)
            }
        }
    }

    suspend fun getPet(petId: String): PetInfo? {
        return petStore.petListFlow.first().find { it.id == petId }
    }
    suspend fun existsByPath(path: String): Boolean = petStore.petListFlow.first().any { it.spritesheetPath == path }
    suspend fun existsBySourceId(sourceId: String): Boolean = sourceId.isNotEmpty() && petStore.petListFlow.first().any { it.sourceId == sourceId }
    fun isFirstLaunch(): Flow<Boolean> = petStore.isFirstLaunch
    fun samplesLoaded(): Flow<Boolean> = petStore.samplesLoaded

    suspend fun setFirstLaunchDone() {
        petStore.setFirstLaunchDone()
    }

    suspend fun setSamplesLoaded() {
        petStore.setSamplesLoaded()
    }
}
