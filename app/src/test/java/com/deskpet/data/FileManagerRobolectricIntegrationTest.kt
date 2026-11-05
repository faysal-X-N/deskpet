package com.deskpet.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文件层集成测试（Robolectric，真实 Context + 真实 BitmapFactory + 真实 zip）。
 *
 * 覆盖的是本仓最容易被"推理代替验证"的三条路径：
 * 1. Codex 素材包导入（解压 → 解析 pet.json → 落库）
 * 2. 恢复备份时缺精灵图必须整条跳过（第四轮修复）
 * 3. 导出缓存只清理 1 小时前的旧包（第六轮修复）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileManagerRobolectricIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: Context
    private lateinit var fm: FileManager

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        fm = FileManager(ctx)
    }

    // ===== 工具 =====

    /** 8×9 的合法精灵表（1px 格子），够 BitmapFactory 解码即可 */
    private fun tinySpritesheetPng(): ByteArray {
        val bmp = Bitmap.createBitmap(SpritesheetParserCols, SpritesheetParserRows, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.RED)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile("pack_${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return f
    }

    private fun petJson(id: String, name: String, sheet: String = "spritesheet.png") =
        """{"id":"$id","displayName":"$name","description":"","spritesheetPath":"$sheet"}""".toByteArray()

    private fun uriOf(f: File): Uri = Uri.fromFile(f)

    // ===== 1. 导入 =====

    @Test
    fun `importCodexPet 能解压、解析 pet_json 并落盘`() = runBlocking {
        val zip = zipOf(
            "pet.json" to petJson("unit-test", "单测宠物"),
            "spritesheet.png" to tinySpritesheetPng()
        )

        val pet = fm.importCodexPet(uriOf(zip))

        assertEquals(PetType.CODEX_PET, pet.type)
        assertEquals("单测宠物", pet.displayName)
        assertEquals("unit-test", pet.sourceId)
        assertNotNull(pet.petJsonPath)
        assertTrue("pet.json 应落地", File(pet.petJsonPath!!).exists())
        assertTrue("精灵表应落地", File(pet.spritesheetPath).exists())
    }

    @Test
    fun `importCodexPet 缺少 pet_json 时抛 InvalidFormat 且不留目录`() = runBlocking {
        val zip = zipOf("spritesheet.png" to tinySpritesheetPng())
        val before = petsDirFileCount()

        val err = runCatching { fm.importCodexPet(uriOf(zip)) }.exceptionOrNull()

        assertTrue("应抛 ImportException", err is com.deskpet.data.model.ImportException)
        assertEquals("失败不应留下孤儿目录", before, petsDirFileCount())
    }

    // ===== 2. 恢复备份：缺精灵图必须跳过 =====

    @Test
    fun `restoreFromBackup 遇到缺精灵图的条目整条跳过`() = runBlocking {
        val store = PetStore(ctx)
        val repo = PetRepository(store)

        // 备份里有一条 pets.json 记录，但 zip 里根本没有对应素材
        val zip = zipOf("pets.json" to """[{"id":"ghost","type":"CODEX_PET","displayName":"幽灵","spritesheetPath":"spritesheet.webp"}]""".toByteArray())

        fm.restoreFromBackup(uriOf(zip), repo)

        assertNull("缺素材的条目不应入库", repo.getPet("ghost"))
        assertTrue("也不应留下半成品目录", !File(ctx.filesDir, "pets/ghost").exists())
    }

    // ===== 3. 导出缓存：只清 1 小时前的 =====

    @Test
    fun `exportPetToZip 只清理超过一小时的旧导出包`() = runBlocking {
        val dir = File(ctx.filesDir, "pets/export-src").also { it.mkdirs() }
        val sheet = File(dir, "spritesheet.png").apply { writeBytes(tinySpritesheetPng()) }
        File(dir, "pet.json").apply { writeBytes(petJson("export-src", "导出源")) }
        val pet = PetInfo(
            id = "export-src", type = PetType.CODEX_PET, displayName = "导出源",
            petJsonPath = File(dir, "pet.json").absolutePath,
            spritesheetPath = sheet.absolutePath, sourceId = "export-src"
        )

        val stale = File(ctx.cacheDir, "old_export.zip").apply {
            writeText("stale"); setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        }
        val fresh = File(ctx.cacheDir, "recent_export.zip").apply { writeText("fresh") }

        val out = fm.exportPetToZip(pet)

        assertNotNull("导出应成功", out)
        assertTrue("2 小时前的旧包应被清理", !stale.exists())
        assertTrue("刚生成的包必须保留（可能在分享中）", fresh.exists())
    }

    // ===== 4. GIF 校验 =====

    @Test
    fun `importGif 拒绝只有魔数的伪造文件`() = runBlocking {
        val fake = tmp.newFile("fake.gif").apply {
            writeBytes("GIF89a".toByteArray() + ByteArray(64))
        }

        val err = runCatching { fm.importGif(uriOf(fake)) }.exceptionOrNull()

        assertTrue("伪造 GIF 应被拒绝", err is com.deskpet.data.model.ImportException)
    }

    // ===== 辅助 =====

    private fun petsDirFileCount(): Int =
        File(ctx.filesDir, "pets").listFiles()?.size ?: 0

    private companion object {
        const val SpritesheetParserCols = 8
        const val SpritesheetParserRows = 9
    }
}
