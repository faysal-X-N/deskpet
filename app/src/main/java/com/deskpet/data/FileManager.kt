package com.deskpet.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import java.util.zip.ZipOutputStream

class FileManager(private val context: Context) {

    private val petDir: File
        get() = File(context.filesDir, "pets").also { it.mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }
    private val zipExtractor = ZipExtractor()

    companion object {
        private const val TAG = "FileManager"
        private const val MAX_GIF_BYTES = 10L * 1024 * 1024
        private const val TMP_STALE_MS = 24L * 60 * 60 * 1000
        /** 导入时智能转码阈值：超过 1.5MB 的 PNG/无损 WebP 才值得重编码 */
        private const val RECODE_THRESHOLD_BYTES = 1_500_000L
        /** 有损 WebP 质量 85：实测 PSNR ≥40dB（肉眼不可辨），alpha 仍走无损边缘不受损 */
        private const val RECODE_QUALITY = 85
    }

    // ===== Import =====

    suspend fun importCodexPet(uri: Uri): PetInfo {
        val id = java.util.UUID.randomUUID().toString()
        val destDir = File(petDir, id).also { it.mkdirs() }
        return try {
            context.contentResolver.openInputStream(uri)?.use { zipExtractor.extract(it, destDir) }
                ?: throw ImportException.FileNotFound("无法打开文件")
            val petJsonFile = findPetJson(destDir)
                ?: throw ImportException.InvalidFormat("缺少 pet.json 文件")
            // 防 OOM：pet.json 正常只有几 KB；恶意 zip 塞一个巨大 JSON 进来 readText 会直接内存溢出
            if (!ZipExtractor.isPetJsonSizeOk(petJsonFile)) throw ImportException.InvalidFormat("pet.json 文件过大")
            val config: CodexPetConfig = try {
                json.decodeFromString(petJsonFile.readText())
            } catch (e: Exception) {
                throw ImportException.InvalidFormat("pet.json 解析失败：${e.message}")
            }
            val spritesheetFile = findSpritesheet(destDir)
                ?: throw ImportException.InvalidFormat("缺少 spritesheet.webp 或 spritesheet.png")
            // 导入时智能转码：大体积 PNG/无损 WebP → 有损 WebP，省用户存储
            recompressSpritesheetIfNeeded(spritesheetFile)
            PetInfo(id = id, type = PetType.CODEX_PET, displayName = config.displayName,
                petJsonPath = petJsonFile.absolutePath, spritesheetPath = spritesheetFile.absolutePath,
                sourceId = config.id)
        } catch (e: ImportException) { destDir.deleteRecursively(); throw e }
        catch (e: Exception) { destDir.deleteRecursively(); throw ImportException.ExtractionFailed(e) }
    }

    /**
     * 导入时智能转码（调研修正版）：社区素材包常携带大体积 PNG / 无损 WebP，
     * 超过阈值则解码 → 有损 WebP 重编码，为用户省存储。
     * 护栏（每条都是硬约束）：
     * 1. 只重编码、绝不缩尺寸——pet.json 网格按像素写死，缩放会帧错位
     * 2. BitmapFactory 软件解码——HARDWARE 位图无法 compress（调研坑 1）
     * 3. 产物需通过"可解码 + 尺寸一致 + 更小"三重校验才替换，任一失败保留原文件
     * 4. GIF 不处理（系统无 GIF 编码器；渲染端已有 1024px 降采样管内存）
     * 调用方保证运行在 Dispatchers.IO（解码约 11MB 峰值 + 编码 1~2 秒）。
     */
    private fun recompressSpritesheetIfNeeded(file: File) {
        if (file.length() <= RECODE_THRESHOLD_BYTES) return
        var src: Bitmap? = null
        val tmp = File(file.parentFile, file.name + ".recode.tmp")
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP // API 26~29：quality<100 即有损
            FileOutputStream(tmp).use { out -> if (!src.compress(fmt, RECODE_QUALITY, out)) return }
            // 校验 1：产物确实更小
            if (tmp.length() >= file.length()) return
            // 校验 2：产物可解码且尺寸与源完全一致（帧网格不被破坏）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth != src.width || bounds.outHeight != src.height) return
            // 全部通过才替换；rename 失败等意外一律保留原文件
            if (!tmp.renameTo(file)) return
        } catch (e: Exception) {
            Log.w(TAG, "spritesheet recompress skipped: ${e.message}")
        } finally {
            src?.recycle()
            tmp.delete() // 未替换成功时清掉临时文件；已替换时它已不存在，无害
        }
    }

    suspend fun importGif(uri: Uri): PetInfo {
        // 清理历史孤儿 .tmp：复制中途进程被杀会留下 tmp 文件，永久累积
        cleanupStaleTmpFiles()
        val id = java.util.UUID.randomUUID().toString()
        val destFile = File(petDir, "$id.gif")
        val tmpFile = File(petDir, "$id.tmp")
        // 文件名要从 ContentResolver 查；uri.lastPathSegment 在不少机型上是 "msf:1000000123" 这种 ID
        val fileName = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "pet.gif"
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipExtractor.copyWithLimit(input, tmpFile, MAX_GIF_BYTES)
            } ?: throw ImportException.FileNotFound("无法打开文件")
            if (!ZipExtractor.isGifFile(tmpFile)) throw ImportException.InvalidFormat("所选文件不是 GIF 格式")
            if (!tmpFile.renameTo(destFile)) { tmpFile.delete(); throw ImportException.ExtractionFailed(RuntimeException("无法移动文件")) }
            // sourceId = 文件名 + 字节数：两个内容不同但同名的 GIF 不会被误判为重复
            PetInfo(id = id, type = PetType.GIF, displayName = fileName, spritesheetPath = destFile.absolutePath,
                sourceId = "$fileName:${destFile.length()}")
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
                zipExtractor.extract(inputStream, tmpDir)
                val jsonFile = File(tmpDir, "pets.json")
                if (jsonFile.exists()) {
                    val petList: List<PetInfo> = try {
                        json.decodeFromString(jsonFile.readText())
                    } catch (e: Exception) {
                        throw ImportException.InvalidFormat("pets.json 解析失败：${e.message}")
                    }
                    val petsDir = File(context.filesDir, "pets").also { it.mkdirs() }
                    val petsBase = petsDir.canonicalPath
                    petList.forEach { pet ->
                        // 去重：已存在的 ID 跳过
                        if (petRepository.getPet(pet.id) != null) return@forEach
                        // 安全校验：id 来自备份 JSON（外部输入），含路径分隔符或 ".." 时可写穿
                        // 到应用私有目录任意位置（如 shared_prefs），必须拒绝
                        if (!ZipExtractor.isSafePetId(pet.id)) return@forEach
                        val destDir = File(petsDir, pet.id)
                        if (!destDir.canonicalPath.startsWith(petsBase + File.separator)) return@forEach
                        destDir.mkdirs()
                        val srcDir = File(tmpDir, "pets/${pet.id}")
                        if (srcDir.exists()) {
                            // copyRecursively 支持嵌套子目录；copyTo 遇到目录会抛 FileNotFoundException。
                            // 它返回 Boolean 且默认策略是静默跳过失败项——磁盘满时会"恢复成功"但文件缺失，
                            // 所以必须检查返回值
                            if (!srcDir.copyRecursively(destDir, true)) {
                                destDir.deleteRecursively()
                                throw ImportException.ExtractionFailed(RuntimeException("恢复宠物 ${pet.id} 的文件时失败（存储空间不足？）"))
                            }
                        }
                        val fixedPet = pet.copy(
                            petJsonPath = findPetJson(destDir)?.absolutePath,
                            spritesheetPath = findSpritesheet(destDir)?.absolutePath ?: pet.spritesheetPath
                        )
                        petRepository.addPetDirect(fixedPet)
                    }
                } else {
                    val petJsonFile = findPetJson(tmpDir)
                    if (petJsonFile == null) {
                        // 之前这里静默返回，用户会看到"恢复完成"但其实什么都没做
                        throw ImportException.InvalidFormat("不是有效的备份文件（缺少 pets.json）")
                    }
                    if (!ZipExtractor.isPetJsonSizeOk(petJsonFile)) throw ImportException.InvalidFormat("pet.json 文件过大")
                    val id = java.util.UUID.randomUUID().toString()
                    val destDir = File(context.filesDir, "pets/$id").also { it.mkdirs() }
                    tmpDir.copyRecursively(destDir, true)
                    val spritesheetFile = findSpritesheet(destDir)
                        ?: throw ImportException.InvalidFormat("备份中缺少 spritesheet")
                    val config: CodexPetConfig = try {
                        json.decodeFromString(petJsonFile.readText())
                    } catch (e: Exception) {
                        throw ImportException.InvalidFormat("pet.json 解析失败：${e.message}")
                    }
                    petRepository.addPetDirect(PetInfo(id = id, type = PetType.CODEX_PET,
                        displayName = config.displayName,
                        petJsonPath = petJsonFile.copyTo(File(destDir, "pet.json"), true).absolutePath,
                        spritesheetPath = spritesheetFile.absolutePath,
                        sourceId = config.id))
                }
            } finally { tmpDir.deleteRecursively() }
        } ?: throw ImportException.FileNotFound("无法打开文件")
    }

    fun shareZip(zipFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出宠物"))
    }

    // ===== Helpers =====

    private fun addPetFilesToZip(zip: ZipOutputStream, pet: PetInfo, prefix: String) {
        val dir = if (pet.type == PetType.CODEX_PET) File(pet.spritesheetPath).parentFile else null
        if (dir != null && dir.exists()) {
            dir.walkTopDown().forEach { file ->
                if (file == dir) return@forEach
                // 缩略图缓存只在列表页用，不打包进导出/备份 zip（体积浪费）
                if (file.name.startsWith("thumb")) return@forEach
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

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        Log.w(TAG, "query display name failed", e)
        null
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

    /** 递归查找 pet.json——很多素材包 zip 会在根目录套一层文件夹 */
    private fun findPetJson(dir: File): File? {
        File(dir, "pet.json").takeIf { it.exists() }?.let { return it }
        dir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            findPetJson(child)?.let { return it }
        }
        return null
    }

    /** 删除超过 24 小时的 .tmp 残留（正在进行的导入不会超过这个时限） */
    private fun cleanupStaleTmpFiles() {
        petDir.listFiles { f -> f.name.endsWith(".tmp") && System.currentTimeMillis() - f.lastModified() > TMP_STALE_MS }
            ?.forEach { it.delete() }
    }
}
