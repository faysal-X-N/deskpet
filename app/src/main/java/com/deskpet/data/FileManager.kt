package com.deskpet.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileManager(private val context: Context) {

    private val petDir: File
        get() = File(context.filesDir, "pets").also { it.mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }

    // ===== Import =====

    suspend fun importCodexPet(uri: Uri): PetInfo {
        val id = java.util.UUID.randomUUID().toString()
        val destDir = File(petDir, id).also { it.mkdirs() }
        return try {
            context.contentResolver.openInputStream(uri)?.use { extractZip(it, destDir) }
                ?: throw ImportException.FileNotFound("无法打开文件")
            val petJsonFile = File(destDir, "pet.json")
            if (!petJsonFile.exists()) throw ImportException.InvalidFormat("缺少 pet.json 文件")
            val config: CodexPetConfig = try {
                json.decodeFromString(petJsonFile.readText())
            } catch (e: Exception) {
                throw ImportException.InvalidFormat("pet.json 解析失败：${e.message}")
            }
            val spritesheetFile = findSpritesheet(destDir)
                ?: throw ImportException.InvalidFormat("缺少 spritesheet.webp 或 spritesheet.png")
            PetInfo(id = id, type = PetType.CODEX_PET, displayName = config.displayName,
                petJsonPath = petJsonFile.absolutePath, spritesheetPath = spritesheetFile.absolutePath,
                sourceId = config.id)
        } catch (e: ImportException) { destDir.deleteRecursively(); throw e }
        catch (e: Exception) { destDir.deleteRecursively(); throw ImportException.ExtractionFailed(e) }
    }

    suspend fun importGif(uri: Uri): PetInfo {
        val id = java.util.UUID.randomUUID().toString()
        val destFile = File(petDir, "$id.gif")
        val tmpFile = File(petDir, "$id.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ImportException.FileNotFound("无法打开文件")
            if (!isGifFormat(tmpFile)) throw ImportException.InvalidFormat("所选文件不是 GIF 格式")
            if (tmpFile.length() > 10L * 1024 * 1024)
                throw ImportException.FileTooLarge(tmpFile.length() / 1024 / 1024, 10)
            if (!tmpFile.renameTo(destFile)) { tmpFile.delete(); throw ImportException.ExtractionFailed(RuntimeException("无法移动文件")) }
            val fileName = uri.lastPathSegment ?: "unknown.gif"
            PetInfo(id = id, type = PetType.GIF, displayName = fileName, spritesheetPath = destFile.absolutePath,
                sourceId = uri.lastPathSegment ?: "")
        } catch (e: ImportException) { destFile.delete(); tmpFile.delete(); throw e }
        catch (e: Exception) { destFile.delete(); tmpFile.delete(); throw ImportException.ExtractionFailed(e) }
    }

    fun deletePet(petInfo: PetInfo) {
        when (petInfo.type) {
            PetType.CODEX_PET -> petInfo.petJsonPath?.let { File(it).parentFile?.deleteRecursively() }
            PetType.GIF -> File(petInfo.spritesheetPath).delete()
        }
    }

    // ===== Export Single =====

    suspend fun exportPetToZip(petInfo: PetInfo): File? {
        val safeName = petInfo.displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val zipFile = File(context.cacheDir, "${safeName}_export.zip")
        return try {
            FileOutputStream(zipFile).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    ZipOutputStream(bos).use { z -> addPetFilesToZip(z, petInfo, "") }
                }
            }
            zipFile
        } catch (e: Exception) { Log.w("FileManager", "exportPet failed", e); zipFile.delete(); null }
    }

    // ===== Backup All =====

    suspend fun exportAllToZip(petList: List<PetInfo>): File? {
        val zipFile = File(context.cacheDir, "DeskPet_backup.zip")
        return try {
            FileOutputStream(zipFile).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    ZipOutputStream(bos).use { z ->
                        // 手动拼 JSON，不依赖序列化库的类型推断
                        z.putNextEntry(ZipEntry("pets.json"))
                        val sanitizedList = petList.map { pet ->
                            pet.copy(
                                petJsonPath = pet.petJsonPath?.let { File(it).name },
                                spritesheetPath = File(pet.spritesheetPath).name
                            )
                        }
                        z.write(buildPetsJson(sanitizedList).toByteArray())
                        z.closeEntry()
                        // 每个宠物的素材文件
                        petList.forEach { addPetFilesToZip(z, it, "pets/${it.id}/") }
                    }
                }
            }
            zipFile
        } catch (e: Exception) { Log.w("FileManager", "backup failed", e); zipFile.delete(); null }
    }

    // ===== Restore =====

    suspend fun restoreFromBackup(uri: Uri, petRepository: PetRepository) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val tmpDir = File(context.cacheDir, "restore_tmp").also { it.deleteRecursively(); it.mkdirs() }
            try {
                extractZip(inputStream, tmpDir)
                val jsonFile = File(tmpDir, "pets.json")
                if (jsonFile.exists()) {
                    val petList: List<PetInfo> = json.decodeFromString(jsonFile.readText())
                    val petsDir = File(context.filesDir, "pets").also { it.mkdirs() }
                    petList.forEach { pet ->
                        // 去重：已存在的 ID 跳过
                        if (petRepository.getPet(pet.id) != null) return@forEach
                        val destDir = File(petsDir, pet.id).also { it.mkdirs() }
                        val srcDir = File(tmpDir, "pets/${pet.id}")
                        if (srcDir.exists()) {
                            srcDir.listFiles()?.forEach { it.copyTo(File(destDir, it.name), true) }
                        }
                        val fixedPet = pet.copy(
                            petJsonPath = File(destDir, "pet.json").takeIf { it.exists() }?.absolutePath,
                            spritesheetPath = findSpritesheet(destDir)?.absolutePath ?: pet.spritesheetPath
                        )
                        petRepository.addPetDirect(fixedPet)
                    }
                } else {
                    val petJsonFile = File(tmpDir, "pet.json")
                    if (petJsonFile.exists()) {
                        val id = java.util.UUID.randomUUID().toString()
                        val destDir = File(context.filesDir, "pets/$id").also { it.mkdirs() }
                        tmpDir.listFiles()?.forEach { it.copyTo(File(destDir, it.name), true) }
                        val spritesheetFile = findSpritesheet(destDir)
                        if (spritesheetFile != null) {
                            val config: CodexPetConfig = json.decodeFromString(petJsonFile.readText())
                            petRepository.addPetDirect(PetInfo(id = id, type = PetType.CODEX_PET,
                                displayName = config.displayName,
                                petJsonPath = File(destDir, "pet.json").absolutePath,
                                spritesheetPath = spritesheetFile.absolutePath))
                        }
                    }
                }
            } finally { tmpDir.deleteRecursively() }
        }
    }

    fun shareZip(zipFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "导出宠物"))
        zipFile.deleteOnExit()
    }

    // ===== Helpers =====

    private fun addPetFilesToZip(zip: ZipOutputStream, pet: PetInfo, prefix: String) {
        val dir = if (pet.type == PetType.CODEX_PET) File(pet.spritesheetPath).parentFile else null
        if (dir != null && dir.exists()) {
            dir.walkTopDown().forEach { file ->
                if (file == dir) return@forEach
                val entryName = (prefix + dir.toPath().relativize(file.toPath()).toString())
                    .replace('\\', '/')
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$entryName/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } else {
            val gif = File(pet.spritesheetPath)
            if (gif.exists()) {
                zip.putNextEntry(ZipEntry(prefix + gif.name))
                gif.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun buildPetsJson(petList: List<PetInfo>): String {
        return json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PetInfo.serializer()), petList)
    }

    private fun extractZip(inputStream: InputStream, destDir: File) {
        val base = destDir.canonicalPath
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (!file.canonicalPath.startsWith(base + File.separator) && file.canonicalPath != base)
                    throw ImportException.InvalidFormat("压缩包包含非法路径：${entry.name}")
                if (entry.isDirectory) file.mkdirs()
                else { file.parentFile?.mkdirs(); file.outputStream().use { zis.copyTo(it) } }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
    }

    private fun findSpritesheet(dir: File): File? {
        File(dir, "spritesheet.webp").takeIf { it.exists() }?.let { return it }
        File(dir, "spritesheet.png").takeIf { it.exists() }?.let { return it }
        // GIF 宠物
        dir.listFiles()?.find { it.name.endsWith(".gif", ignoreCase = true) }?.let { return it }
        // 递归子目录
        dir.listFiles()?.filter { it.isDirectory }?.forEach {
            findSpritesheet(it)?.let { f -> return f }
        }
        return null
    }

    private fun isGifFormat(file: File): Boolean {
        val header = ByteArray(6)
        file.inputStream().use { if (it.read(header) < 6) return false }
        return header.contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61))
            || header.contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
    }
}
