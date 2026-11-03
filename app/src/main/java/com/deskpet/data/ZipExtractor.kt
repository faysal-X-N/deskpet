package com.deskpet.data

import com.deskpet.data.model.ImportException
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * zip 解压核心逻辑（纯 JVM，无 Android 依赖，可在单元测试中直接用恶意文件攻击）。
 *
 * 内置防护：
 * - 路径穿越校验（canonicalPath 必须落在目标目录内）
 * - 条目数量上限
 * - 解压后总大小上限（防 zip 炸弹）
 */
class ZipExtractor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
) {

    fun extract(inputStream: InputStream, destDir: File) {
        val base = destDir.canonicalPath
        var entries = 0
        var total = 0L
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (++entries > maxEntries) throw ImportException.InvalidFormat("压缩包文件数量过多")
                val file = File(destDir, entry.name)
                if (!file.canonicalPath.startsWith(base + File.separator) && file.canonicalPath != base)
                    throw ImportException.InvalidFormat("压缩包包含非法路径：${entry.name}")
                if (entry.isDirectory) file.mkdirs()
                else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        total += copyLimited(zis, out, maxTotalBytes - total, maxTotalBytes)
                    }
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
    }

    /** 带总量上限的流复制，防止 zip 炸弹 / 超大文件占满存储 */
    private fun copyLimited(input: InputStream, out: java.io.OutputStream, remaining: Long, limitBytes: Long): Long {
        var copied = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            copied += n
            if (copied > remaining) {
                throw ImportException.FileTooLarge(copied / 1024 / 1024, limitBytes / 1024 / 1024)
            }
            out.write(buf, 0, n)
        }
        return copied
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
        const val DEFAULT_MAX_TOTAL_BYTES = 200L * 1024 * 1024
        const val MAX_PET_JSON_BYTES = 1024L * 1024

        /**
         * pet.id 来自备份 pets.json（外部输入）。
         * 含路径分隔符或 ".." 时可把文件写穿到应用私有目录任意位置（如 shared_prefs）。
         */
        fun isSafePetId(id: String): Boolean =
            id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")

        /** GIF 魔数校验（GIF87a / GIF89a），拒绝伪装成 GIF 的其它文件 */
        fun isGifFile(file: File): Boolean {
            val header = ByteArray(6)
            file.inputStream().use { if (it.read(header) < 6) return false }
            return header.contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61))
                || header.contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
        }

        /** pet.json 大小防护：正常只有几 KB，超大说明是恶意构造，直接读会 OOM */
        fun isPetJsonSizeOk(file: File): Boolean = file.length() <= MAX_PET_JSON_BYTES

        /** 单文件带上限复制（GIF 导入用） */
        fun copyWithLimit(input: InputStream, dest: File, maxBytes: Long) {
            dest.outputStream().use { out -> copyBounded(input, out, maxBytes, maxBytes) }
        }

        private fun copyBounded(input: InputStream, out: java.io.OutputStream, remaining: Long, limitBytes: Long): Long {
            var copied = 0L
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                copied += n
                if (copied > remaining) {
                    throw ImportException.FileTooLarge(copied / 1024 / 1024, limitBytes / 1024 / 1024)
                }
                out.write(buf, 0, n)
            }
            return copied
        }
    }
}
