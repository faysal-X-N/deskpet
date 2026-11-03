package com.deskpet.data

import android.content.Context
import android.util.Log
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

object SamplePetLoader {

    private const val TAG = "SamplePetLoader"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadSamplePets(context: Context, petRepository: PetRepository) {
        // 用持久化标记判断，而不是"列表为空"：
        // 1) pet_list JSON 解析失败时列表为空，不标记就会把示例宠物重复导入一遍
        // 2) 用户主动删光所有宠物后，也不应该让示例宠物复活
        if (petRepository.samplesLoaded().first()) return

        withContext(Dispatchers.IO) {
            try {
                val assetsPetsDir = "pets"
                val assetList = context.assets.list(assetsPetsDir) ?: return@withContext
                val internalPetsDir = File(context.filesDir, "pets").also { it.mkdirs() }
                var loadedCount = 0

                for (petName in assetList) {
                    try {
                        val srcDir = "$assetsPetsDir/$petName"
                        val destDir = File(internalPetsDir, petName)

                        val petJsonContent = context.assets.open("$srcDir/pet.json")
                            .bufferedReader().readText()
                        val config = json.decodeFromString<CodexPetConfig>(petJsonContent)

                        destDir.mkdirs()
                        val petFiles = context.assets.list(srcDir) ?: continue
                        for (fileName in petFiles) {
                            context.assets.open("$srcDir/$fileName").use { input ->
                                File(destDir, fileName).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }

                        val spritesheetFile = File(destDir, "spritesheet.webp")
                            .takeIf { it.exists() }
                            ?: File(destDir, "spritesheet.png").takeIf { it.exists() }
                            ?: continue

                        val petInfo = PetInfo(
                            id = petName,
                            type = PetType.CODEX_PET,
                            displayName = config.displayName,
                            petJsonPath = File(destDir, "pet.json").absolutePath,
                            spritesheetPath = spritesheetFile.absolutePath,
                            // 记录 sourceId，导入同一只示例宠物时才能被重复检测拦下
                            sourceId = config.id
                        )
                        petRepository.addPetDirect(petInfo)
                        loadedCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load sample pet: $petName", e)
                    }
                }

                // 只要成功导入过一只（或 assets 本来就没有示例）就打标记，避免反复重试
                if (loadedCount > 0 || assetList.isEmpty()) {
                    petRepository.setSamplesLoaded()
                }

                val allPets = petRepository.getAllPets().first()
                allPets.firstOrNull()?.let { petRepository.setActivePet(it.id) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sample pets", e)
            }
        }
    }
}
