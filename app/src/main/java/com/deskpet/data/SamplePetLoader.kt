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
        val allPets = petRepository.getAllPets().first()
        if (allPets.isNotEmpty()) return  // 已经加载过，跳过

        withContext(Dispatchers.IO) {
            try {
                val assetsPetsDir = "pets"
                val assetList = context.assets.list(assetsPetsDir) ?: return@withContext
                val internalPetsDir = File(context.filesDir, "pets").also { it.mkdirs() }

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
                            spritesheetPath = spritesheetFile.absolutePath
                        )
                        petRepository.addPetDirect(petInfo)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load sample pet: $petName", e)
                    }
                }

                val allPets = petRepository.getAllPets().first()
                allPets.firstOrNull()?.let { petRepository.setActivePet(it.id) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sample pets", e)
            }
        }
    }
}
