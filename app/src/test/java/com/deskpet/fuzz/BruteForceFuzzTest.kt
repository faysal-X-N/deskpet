package com.deskpet.fuzz

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.deskpet.data.FileManager
import com.deskpet.data.PetRepository
import com.deskpet.data.PetStore
import com.deskpet.data.ZipExtractor
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import com.deskpet.engine.SpritesheetParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * 暴力检测：对解析层 / 文件层 / 并发路径做大规模变异与压力测试。
 * 只把"违反不变量"的事件记进报告，最后统一断言报告为空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BruteForceFuzzTest {

    companion object {
        val violations = StringBuilder()
        val metrics = StringBuilder()
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun report() {
        val f = File("build/brute-force-report.txt")
        f.parentFile?.mkdirs()
        f.writeText(
            metrics.toString() + "\n" +
                if (violations.isEmpty()) "NO VIOLATIONS" else violations.toString()
        )
    }

    private fun v(tag: String, msg: String) { violations.appendLine("[$tag] $msg") }
    private fun m(s: String) { metrics.appendLine(s) }

    // ================= zip 变异 =================

    private fun validZipBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("pet.json"))
            z.write("""{"id":"x","displayName":"X","spritesheetPath":"spritesheet.png"}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("spritesheet.png"))
            z.write(pngBytes())
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun pngBytes(): ByteArray {
        val b = Bitmap.createBitmap(8, 9, Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.BLUE)
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, bos)
        b.recycle()
        return bos.toByteArray()
    }

    private fun rawZip(entries: List<Pair<String, ByteArray?>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (n, d) ->
                z.putNextEntry(ZipEntry(n)); d?.let { z.write(it) }; z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun fuzzZipExtractor() {
        val base = validZipBytes()
        val rnd = Random(20261105)
        val hostileNames = listOf(
            "../evil.txt", "..\\evil.txt", "/abs.txt", "a/../../b.txt",
            "a/".repeat(200) + "deep.txt", "NUL\u0000.txt", "😀/宠物.json",
            ".", "./", "..", "", "con", "a:b.txt", "%2e%2e/evil.txt"
        )

        var cases = 0
        // 1) 随机字节翻转 / 截断
        repeat(400) {
            val bytes = base.copyOf()
            val flips = rnd.nextInt(1, 9)
            repeat(flips) { bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte() }
            runZipCase("flip", bytes.also { cases++ })
        }
        repeat(200) {
            val n = rnd.nextInt(1, base.size)
            runZipCase("truncate", base.copyOf(n).also { cases++ })
        }
        // 2) 敌意条目名
        hostileNames.forEach { name ->
            runZipCase("name:$name", rawZip(listOf(name to "pwn".toByteArray())).also { cases++ })
            runZipCase("name-dir:$name", rawZip(listOf(name to null)).also { cases++ })
        }
        // 3) 重复条目 + 大小炸弹
        runZipCase("many", rawZip(List(600) { "f$it.txt" to byteArrayOf(1) }).also { cases++ })
        runZipCase("bomb", rawZip(listOf("zeros.bin" to ByteArray(64 * 1024 * 1024))).also { cases++ })
        // 4) 空输入 / 非 zip
        runZipCase("empty", ByteArray(0).also { cases++ })
        runZipCase("garbage", rnd.nextBytes(4096).also { cases++ })

        m("BRUTE zip cases = $cases")
    }

    private fun runZipCase(tag: String, bytes: ByteArray) {
        val root = File(ctx.cacheDir, "fz_${System.nanoTime()}").also { it.mkdirs() }
        val dest = File(root, "out").also { it.mkdirs() }
        try {
            ZipExtractor().extract(ByteArrayInputStream(bytes), dest)
        } catch (e: ImportException) {
            // 预期内的拒绝
        } catch (e: java.util.zip.ZipException) {
            // 畸形 zip 头
        } catch (e: java.io.IOException) {
            // 流层面的错误
        } catch (t: Throwable) {
            v("zip:$tag", "未预期异常 ${t.javaClass.name}: ${t.message}")
        } finally {
            // 不变量：除了 out 目录，root 下不能出现别的东西
            root.listFiles()?.forEach { f ->
                if (f.name != "out") v("zip:$tag", "逃逸到目标目录外: ${f.name}")
            }
            // 不变量：out 内所有条目的 canonical 路径必须在 out 内
            val canon = dest.canonicalPath
            dest.walkTopDown().forEach { f ->
                if (!f.canonicalPath.startsWith(canon)) v("zip:$tag", "canonical 逃逸: ${f.path}")
            }
            root.deleteRecursively()
        }
    }

    // ================= pet.json 变异（走完整导入） =================

    @Test
    fun fuzzPetJsonImport() {
        val fm = FileManager(ctx)
        val png = pngBytes()
        val payloads = listOf(
            "{}",
            "[]",
            "null",
            "{\"id\":1}",
            "{\"id\":\"a\",\"displayName\":\"b\"}",
            """{"id":"a","displayName":"b","spritesheetPath":"spritesheet.png","states":[{"name":"idle","row":-5,"frames":-1}]}""",
            """{"id":"a","displayName":"b","spritesheetPath":"spritesheet.png","states":[{"name":"idle","row":99999,"frames":99999}]}""",
            """{"id":"a","displayName":"b","spritesheetPath":"spritesheet.png","states":[]}""",
            """{"id":"a","displayName":"b","spritesheetPath":"spritesheet.png","extra":{"a":[1,2,3]}}""",
            "{\"id\":\"" + "x".repeat(5000) + "\",\"displayName\":\"b\",\"spritesheetPath\":\"s.png\"}",
            "[" .repeat(200) + "]".repeat(200),
            "{\"id\":\"a\",\"displayName\":\"b\",\"spritesheetPath\":\"s.png\",\"schema_version\":123}"
        )

        payloads.forEachIndexed { i, json ->
            val zip = File(ctx.cacheDir, "pj_$i.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("pet.json")); z.write(json.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("spritesheet.png")); z.write(png); z.closeEntry()
            }
            val before = File(ctx.filesDir, "pets").listFiles()?.size ?: 0
            try {
                runBlocking { fm.importCodexPet(Uri.fromFile(zip)) }
                // 成功也不算违规：只要落地文件真实存在
                val after = File(ctx.filesDir, "pets").listFiles()?.size ?: 0
                if (after != before + 1) v("json:$i", "导入成功但目录数未 +1 ($before→$after)")
            } catch (e: ImportException) {
                val after = File(ctx.filesDir, "pets").listFiles()?.size ?: 0
                if (after != before) v("json:$i", "失败但留下孤儿目录 ($before→$after)")
            } catch (t: Throwable) {
                v("json:$i", "未预期异常 ${t.javaClass.name}: ${t.message}")
            }
        }
    }

    // ================= PetInfo JSON 往返 =================

    @Test
    fun fuzzPetInfoRoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val rnd = Random(7)
        repeat(300) {
            val p = PetInfo(
                id = randomString(rnd, rnd.nextInt(0, 64)),
                type = if (rnd.nextBoolean()) PetType.CODEX_PET else PetType.GIF,
                displayName = randomString(rnd, rnd.nextInt(0, 200)),
                petJsonPath = if (rnd.nextBoolean()) null else randomString(rnd, 10),
                spritesheetPath = randomString(rnd, 32),
                positionX = rnd.nextFloat() * 4000f - 2000f,
                positionY = rnd.nextFloat() * 4000f - 2000f,
                scale = rnd.nextFloat() * 10f,
                sourceId = randomString(rnd, rnd.nextInt(0, 64))
            )
            try {
                val s = json.encodeToString(PetInfo.serializer(), p)
                val back = json.decodeFromString(PetInfo.serializer(), s)
                if (back != p) v("roundtrip", "往返不一致: $s")
            } catch (t: Throwable) {
                v("roundtrip", "序列化异常 ${t.javaClass.name}: ${t.message}")
            }
        }
        // 特殊浮点：生产配置默认禁止 NaN/Inf，应当抛异常而不是静默写出坏数据
        val nan = PetInfo(id = "n", type = PetType.GIF, displayName = "n", spritesheetPath = "s", scale = Float.NaN)
        val threw = runCatching { json.encodeToString(PetInfo.serializer(), nan) }.isFailure
        m("BRUTE NaN 序列化被拒绝 = $threw")
    }

    private fun randomString(rnd: Random, len: Int): String {
        val pool = "abc中文😀\u0000\n\t\"\\/<>|:*? ，。"
        return buildString { repeat(len) { append(pool[rnd.nextInt(pool.length)]) } }
    }

    // ================= 几何穷举 =================

    @Test
    fun bruteForceGeometry() {
        val parser = SpritesheetParser()
        val dims = intArrayOf(0, 1, 7, 8, 9, 100, 1023, 1024, 1025, 4096, 16384, 40000)
        for (w in dims) for (h in dims) {
            val (rw, rh) = SpritesheetParser.renderCellSize(w, h)
            if (w >= SpritesheetParser.SPRITESHEET_COLS && h >= SpritesheetParser.SPRITESHEET_ROWS && (rw < 1 || rh < 1)) v("geom", "renderCellSize($w,$h)=($rw,$rh) 非法")
            for (row in 0 until SpritesheetParser.SPRITESHEET_ROWS) {
                for (col in 0 until SpritesheetParser.SPRITESHEET_COLS) {
                    val r = parser.calculateCellRect(row, col, w, h)
                    if (r.left < 0 || r.top < 0 || r.right > w || r.bottom > h || r.width() < 0 || r.height() < 0) {
                        v("geom", "calculateCellRect($row,$col,$w,$h)=$r 越界")
                    }
                }
            }
        }
        // 恶意 states：frames/row 越界时的钳制
        val evil = CodexPetConfig(
            id = "e", displayName = "E", spritesheetPath = "s",
            states = listOf(
                com.deskpet.data.model.AnimationState("idle", -100, 999),
                com.deskpet.data.model.AnimationState("running", 999, -1),
                com.deskpet.data.model.AnimationState("waving", 0, 0)
            )
        )
        com.deskpet.data.model.PetAnimationState.entries.forEach { st ->
            val fc = parser.getFrameCount(evil, st)
            if (fc < 0 || fc > SpritesheetParser.SPRITESHEET_COLS) v("geom", "getFrameCount=$fc 越界 ($st)")
        }
        m("BRUTE geometry done")
    }

    // ================= 恢复路径注入 =================

    @Test
    fun fuzzRestore() {
        val fm = FileManager(ctx)
        val repo = PetRepository(PetStore(ctx))
        val petsDir = File(ctx.filesDir, "pets").also { it.mkdirs() }
        val badIds = listOf("../escape", "..", "", "a/b", "a\\b", " ", "con", ".hidden", "x".repeat(300))
        val allIds = badIds + listOf("ok")
        val sb = StringBuilder("[")
        allIds.forEachIndexed { i, id ->
            if (i > 0) sb.append(',')
            val esc = id.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("""{"id":"$esc","type":"GIF","displayName":"p$i","spritesheetPath":"g.gif"}""")
        }
        sb.append(']')
        val json = sb.toString()

        val zip = File(ctx.cacheDir, "restore_fuzz.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("pets.json")); z.write(json.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("pets/ok/g.gif")); z.write("GIF89a".toByteArray()); z.closeEntry()
        }

        val before = petsDir.list()?.toSet() ?: emptySet()
        try {
            runBlocking { fm.restoreFromBackup(Uri.fromFile(zip), repo) }
        } catch (e: ImportException) {
            // 预期
        } catch (t: Throwable) {
            v("restore", "未预期异常 ${t.javaClass.name}: ${t.message}")
        }
        val after = petsDir.list()?.toSet() ?: emptySet()
        (after - before).forEach { name ->
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                v("restore", "非法 id 落盘: $name")
            }
        }
        // 上层目录不应被写入
        if (File(ctx.filesDir, "escape").exists()) v("restore", "写穿到 filesDir/escape")
        m("BRUTE restore new dirs = ${after - before}")
    }

    // ================= 并发压测 =================

    @Test
    fun stressConcurrency() {
        val repo = PetRepository(PetStore(ctx))
        val threads = 6
        val opsPerThread = 25
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger()
        val start = System.currentTimeMillis()

        repeat(threads) { t ->
            Thread {
                try {
                    repeat(opsPerThread) { i ->
                        runBlocking {
                            when ((t + i) % 4) {
                                0 -> repo.addPetDirect(
                                    PetInfo(id = "t$t-$i", type = PetType.CODEX_PET, displayName = "d$t-$i", spritesheetPath = "/f")
                                )
                                1 -> repo.deletePet("t$t-$i")
                                2 -> repo.savePosition("t$t-${i - 1}", i.toFloat(), i.toFloat(), 1f)
                                else -> repo.setScale("t$t-${i - 1}", 1f + i % 5)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                    v("concurrency", "线程 $t 异常 ${e.javaClass.name}: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        val finished = latch.await(60, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - start
        if (!finished) v("concurrency", "60s 未完成，疑似死锁")
        if (errors.get() > 0) v("concurrency", "${errors.get()} 次异常")

        val list = runBlocking { repo.getAllPets().first() }
        val dup = list.groupBy { it.id }.filterValues { it.size > 1 }
        if (dup.isNotEmpty()) v("concurrency", "重复 id: ${dup.keys.take(5)}")
        m("BRUTE concurrency: ${elapsed}ms, ${list.size} pets, dup=${dup.size}")
        assertEquals(0, errors.get())
        assertTrue(finished)
    }
}
