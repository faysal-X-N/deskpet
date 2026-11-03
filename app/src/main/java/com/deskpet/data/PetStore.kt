package com.deskpet.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pet_prefs")

class PetStore(private val context: Context) : IPetStore {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val PET_LIST_KEY = stringPreferencesKey("pet_list_json")
        private val ACTIVE_PET_KEY = stringPreferencesKey("active_pet_id")
        private val FIRST_LAUNCH_KEY = booleanPreferencesKey("is_first_launch")
        private val SAMPLES_LOADED_KEY = booleanPreferencesKey("samples_loaded")
    }

    /** 解析失败时回退到最后一次成功的列表，避免一次损坏让用户所有宠物"集体消失"。
     *  多个收集器可能并发进入 map，用锁保护缓存。 */
    private val lastGoodLock = Any()
    private var lastGoodList: List<PetInfo>? = null

    /**
     * 刁钻边界：DataStore 文件本身损坏（磁盘坏块/写入中途断电等）时，
     * data 流会抛 IOException/CorruptionException——下面 map 里的 JSON catch
     * 只能接解析错误，接不住上游流异常，所有收集方会连环崩溃，且每次冷启动
     * 都崩直到用户清数据（"App 打不开"级别事故）。catch 后按空数据继续：
     * App 可启动、用户可重新导入，下一次成功写入会重建文件。
     */
    private fun safeData(ds: DataStore<Preferences>) = ds.data.catch { e ->
        Log.e("PetStore", "DataStore read failed, continuing with empty prefs", e)
        emit(emptyPreferences())
    }

    override val petListFlow: Flow<List<PetInfo>> = safeData(context.dataStore).map { prefs ->
        val jsonStr = prefs[PET_LIST_KEY] ?: "[]"
        try {
            json.decodeFromString<List<PetInfo>>(jsonStr)
        } catch (e: Exception) {
            Log.e("PetStore", "Failed to parse pet list JSON, falling back to last good list", e)
            synchronized(lastGoodLock) { lastGoodList } ?: emptyList()
        }.also { parsed ->
            synchronized(lastGoodLock) { lastGoodList = parsed }
        }
    }

    override suspend fun updatePetList(list: List<PetInfo>) {
        context.dataStore.edit { prefs ->
            prefs[PET_LIST_KEY] = json.encodeToString(list)
        }
    }

    override val activePetIdFlow: Flow<String?> = safeData(context.dataStore).map {
        it[ACTIVE_PET_KEY]
    }

    override suspend fun setActivePetId(id: String) {
        context.dataStore.edit { it[ACTIVE_PET_KEY] = id }
    }

    override val isFirstLaunch: Flow<Boolean> = safeData(context.dataStore).map {
        it[FIRST_LAUNCH_KEY] ?: true
    }

    override suspend fun setFirstLaunchDone() {
        context.dataStore.edit { it[FIRST_LAUNCH_KEY] = false }
    }

    /** 示例宠物是否已加载过。用标记而不是"列表为空"判断，否则 JSON 损坏或用户删光宠物后示例会复活 */
    override val samplesLoaded: Flow<Boolean> = safeData(context.dataStore).map {
        it[SAMPLES_LOADED_KEY] ?: false
    }

    override suspend fun setSamplesLoaded() {
        context.dataStore.edit { it[SAMPLES_LOADED_KEY] = true }
    }
}
