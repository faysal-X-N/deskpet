package com.deskpet.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.flow.Flow
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
    }

    override val petListFlow: Flow<List<PetInfo>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[PET_LIST_KEY] ?: "[]"
        try {
            json.decodeFromString<List<PetInfo>>(jsonStr)
        } catch (e: Exception) {
            Log.e("PetStore", "Failed to parse pet list JSON, resetting", e)
            emptyList()
        }
    }

    override suspend fun updatePetList(list: List<PetInfo>) {
        context.dataStore.edit { prefs ->
            prefs[PET_LIST_KEY] = json.encodeToString(list)
        }
    }

    override val activePetIdFlow: Flow<String?> = context.dataStore.data.map {
        it[ACTIVE_PET_KEY]
    }

    override suspend fun setActivePetId(id: String) {
        context.dataStore.edit { it[ACTIVE_PET_KEY] = id }
    }

    override val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map {
        it[FIRST_LAUNCH_KEY] ?: true
    }

    override suspend fun setFirstLaunchDone() {
        context.dataStore.edit { it[FIRST_LAUNCH_KEY] = false }
    }
}
