package com.deskpet.data

import com.deskpet.data.model.ImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极端模糊测试：用恶意构造的 zip / 文件直接攻击 [ZipExtractor]（导入功能的真实解析代码）。
 * 每个用例对应一种真实攻击手法（参考 fuzzdb / go-fuzz corpus 的经典样本类型）。
 */
class ZipExtractorFuzzTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ===== 恶意 zip 构造工具 =====

    /** 把若干 (entryName, bytes) 打成 zip；entry 传 null 表示写目录条目 */
    private fun makeZip(entries: List<Pair<String, ByteArray?>>): File {
        val f = tmp.newFile("attack_${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                if (data != null) z.write(data)
                z.closeEntry()
            }
        }
        return f
    }

    /** 向 zip 写入若干由零流提供内容的巨型条目（用于构造 zip 炸弹而不占内存） */
    private fun makeZipWithStreams(specs: List<Pair<String, Long>>): File {
        val f = tmp.newFile("bomb_${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            specs.forEach { (name, contentBytes) ->
                z.putNextEntry(ZipEntry(name))
                val zeros = ByteArray(1024 * 1024)
                var remaining = contentBytes
                while (remaining > 0) {
                    val n = minOf(remaining, zeros.size.toLong()).toInt()
                    z.write(zeros, 0, n)
                    remaining -= n
                }
                z.closeEntry()
            }
        }
        return f
    }

    /** 手工构造含重复条目的 zip（ZipOutputStream 拒绝写重复条目，只能绕过它自己拼字节）。
     *  ZipInputStream 只按顺序读 local file header，不需要 central directory。
     *  注意 zip 所有字段都是小端序。 */
    private fun makeDuplicateZip(): File {
        val f = tmp.newFile("dup_${System.nanoTime()}.zip")
        fun entryBytes(name: String, data: ByteArray): ByteArray {
            val nameBytes = name.toByteArray()
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            val buf = java.nio.ByteBuffer.allocate(30 + nameBytes.size + data.size)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0x04034b50)        // local file header 签名
            buf.putShort(20)              // version needed
            buf.putShort(0)               // flags（无 data descriptor）
            buf.putShort(0)               // method = stored
            buf.putShort(0); buf.putShort(0)  // time, date
            buf.putInt(crc.toInt())
            buf.putInt(data.size)         // compressed size
            buf.putInt(data.size)         // uncompressed size
            buf.putShort(nameBytes.size.toShort()); buf.putShort(0) // 文件名长度, extra 长度
            buf.put(nameBytes)
            buf.put(data)
            return buf.array()
        }
        val bytes = entryBytes("pet.json", "{\"a\":1}".toByteArray()) +
                entryBytes("pet.json", "{\"a\":2}".toByteArray())
        f.writeBytes(bytes)
        return f
    }

    // ===== 攻击 1：路径穿越（Zip Slip）=====

    @Test
    fun `目录穿越条目被拒绝`() {
        val zip = makeZip(listOf("../evil.txt" to "pwned".toByteArray()))
        val dest = tmp.newFolder("out1")
        assertThrows(ImportException.InvalidFormat::class.java) {
            ZipExtractor().extract(zip.inputStream(), dest)
        }
        // 确认真的没有写到目标目录之外
        assertFalse(tmp.root.resolve("evil.txt").exists())
    }

    @Test
    fun `深层目录穿越条目被拒绝`() {
        val zip = makeZip(listOf("a/b/../../../evil.txt" to "pwned".toByteArray()))
        val dest = tmp.newFolder("out2")
        assertThrows(ImportException.InvalidFormat::class.java) {
            ZipExtractor().extract(zip.inputStream(), dest)
        }
        assertFalse(tmp.root.resolve("evil.txt").exists())
    }

    @Test
    fun `绝对路径条目不会逃逸目标目录`() {
        val zip = makeZip(listOf("/data/local/tmp/evil.txt" to "pwned".toByteArray()))
        val dest = tmp.newFolder("out3")
        // JVM 的 File(parent, child) 是字符串拼接，"/..." 开头的条目会落在 dest 内部而非系统根目录；
        // 安全属性是「不能逃逸 dest」，而不是必须抛异常
        ZipExtractor().extract(zip.inputStream(), dest)
        assertFalse(tmp.root.resolve("evil.txt").exists())
        assertFalse(tmp.root.resolve("data").exists())
        assertTrue(dest.walkTopDown().any { it.name == "evil.txt" })
    }

    // ===== 攻击 2：zip 炸弹（解压后超总大小上限）=====

    @Test
    fun `zip炸弹_解压总量超限被拒绝`() {
        // 高压缩比的零流：磁盘上几十 KB，解压后 210MB，超过 200MB 默认上限（单 zip 内两个条目）
        val bomb = makeZipWithStreams(listOf(
            "zeros.bin" to 150L * 1024 * 1024,
            "zeros2.bin" to 60L * 1024 * 1024
        ))
        val dest = tmp.newFolder("out4")
        assertThrows(ImportException.FileTooLarge::class.java) {
            ZipExtractor().extract(bomb.inputStream(), dest)
        }
    }

    // ===== 攻击 3：条目数量炸弹 =====

    @Test
    fun `条目数量超限被拒绝`() {
        val entries = (1..501).map { "f_$it.txt" to byteArrayOf(1) }
        val zip = makeZip(entries)
        val dest = tmp.newFolder("out5")
        assertThrows(ImportException.InvalidFormat::class.java) {
            ZipExtractor().extract(zip.inputStream(), dest)
        }
    }

    // ===== 攻击 4：畸形条目 =====

    @Test
    fun `空文件名条目抛异常而不崩溃进程`() {
        val zip = makeZip(listOf("" to byteArrayOf(1)))
        val dest = tmp.newFolder("out6")
        assertThrows(Exception::class.java) {
            ZipExtractor().extract(zip.inputStream(), dest)
        }
    }

    @Test
    fun `同名条目重复出现_不崩溃_后写覆盖`() {
        val zip = makeDuplicateZip()
        val dest = tmp.newFolder("out7")
        ZipExtractor().extract(zip.inputStream(), dest) // 不应抛异常
        assertEquals("{\"a\":2}", dest.resolve("pet.json").readText())
    }

    // ===== 正常场景回归（防护不能误伤合法文件）=====

    @Test
    fun `正常zip_含深层嵌套目录_成功解压`() {
        val deep = (1..100).joinToString("/") { "d$it" }
        val zip = makeZip(listOf(
            "$deep/pet.json" to "{}".toByteArray(),
            "$deep/spritesheet.webp" to ByteArray(2048)
        ))
        val dest = tmp.newFolder("out8")
        ZipExtractor().extract(zip.inputStream(), dest)
        assertTrue(dest.resolve("$deep/pet.json").exists())
        assertTrue(dest.resolve("$deep/spritesheet.webp").exists())
    }

    // ===== pet.id 白名单（防备份文件写穿私有目录）=====

    @Test
    fun `petId安全校验`() {
        assertTrue(ZipExtractor.isSafePetId(UUID.randomUUID().toString()))
        assertTrue(ZipExtractor.isSafePetId("abc123"))
        assertFalse(ZipExtractor.isSafePetId("../shared_prefs"))
        assertFalse(ZipExtractor.isSafePetId("..\\shared_prefs"))
        assertFalse(ZipExtractor.isSafePetId("a/b"))
        assertFalse(ZipExtractor.isSafePetId("a\\b"))
        assertFalse(ZipExtractor.isSafePetId(".."))
        assertFalse(ZipExtractor.isSafePetId("id..x"))
        assertFalse(ZipExtractor.isSafePetId(""))
        assertFalse(ZipExtractor.isSafePetId("   "))
    }

    // ===== GIF 魔数校验（防伪装文件）=====

    @Test
    fun `GIF魔数校验`() {
        fun fileWith(header: ByteArray, tail: Int = 16): File {
            val f = tmp.newFile("img_${System.nanoTime()}.bin")
            f.writeBytes(header + ByteArray(tail))
            return f
        }
        assertTrue(ZipExtractor.isGifFile(fileWith("GIF87a".toByteArray())))
        assertTrue(ZipExtractor.isGifFile(fileWith("GIF89a".toByteArray())))
        // 伪装成 GIF 的 JPEG
        assertFalse(ZipExtractor.isGifFile(fileWith(byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "JFIF".toByteArray())))
        // 少于 6 字节的残缺文件
        val short = tmp.newFile("short.bin")
        short.writeBytes("GIF8".toByteArray())
        assertFalse(ZipExtractor.isGifFile(short))
    }

    // ===== 单文件带上限复制 =====

    @Test
    fun `单文件复制超限被拒绝`() {
        val src = tmp.newFile("big.gif")
        src.writeBytes(ByteArray(2 * 1024 * 1024))
        val dst = tmp.newFile("dst.gif")
        assertThrows(ImportException.FileTooLarge::class.java) {
            ZipExtractor.copyWithLimit(src.inputStream(), dst, 1024L * 1024)
        }
    }

    @Test
    fun `单文件复制正常完成`() {
        val src = tmp.newFile("ok.gif")
        src.writeBytes(ByteArray(1024))
        val dst = tmp.newFile("dst2.gif")
        ZipExtractor.copyWithLimit(src.inputStream(), dst, 1024L * 1024)
        assertEquals(1024L, dst.length())
    }
}
