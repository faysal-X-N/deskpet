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

    suspend fun addPet(petInfo: PetInfo): PetInfo = writeMutex.withLock {
        val withId = petInfo.copy(id = UUID.randomUUID().toString())
        val currentList = petStore.petListFlow.first().toMutableList()
        currentList.add(withId)
        petStore.updatePetList(currentList)
        withId
    }

    /**
     * 直接添加宠物（保持原始 ID，不生成新 UUID）—— 用于恢复备份 / 首次加载示例宠物。
     *
     * 幂等：id 已存在时直接跳过。调用方是"恢复备份"与"首次启动加载 assets"两条
     * 可能重复进入的路径（示例加载在 Activity 重建（如深色模式切换；旋转已由
     * manifest configChanges 接管）后 LaunchedEffect 再跑一次而首次标记还没落盘）。
     * 一旦列表里出现重复 id，LazyVerticalGrid 的
     * items(key = { it.id }) 会直接抛 "Key was already used" 崩溃。
     */
    suspend fun addPetDirect(petInfo: PetInfo) {
        writeMutex.withLock {
            val currentList = petStore.petListFlow.first()
            if (currentList.any { it.id == petInfo.id }) return@withLock
            petStore.updatePetList(currentList + petInfo)
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
        // 只写 activePetId，不再重写宠物列表：选中态由 UI 用 activePetId 实时比较得出，
        // 列表里没有需要同步的状态（曾经的 isActive 死字段已删）
        writeMutex.withLock {
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

    fun overlayAutoResume(): Flow<Boolean> = petStore.overlayAutoResumeFlow

    /** 异步写"恢复意图"标志：通知栏关闭路径在服务销毁瞬间调用，必须 fire-and-forget */
    fun setOverlayAutoResumeAsync(v: Boolean) {
        persistenceScope.launch {
            runCatching { petStore.setOverlayAutoResume(v) }
                .onFailure { android.util.Log.w("PetRepository", "AutoResume flag write failed", it) }
        }
    }
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
