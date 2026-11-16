package com.deskpet.engine

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 图集几何的回归测试：**行数由图片实际高度推**，不再写死 9 行。
 *
 * 背景（实测社区素材）：v1 1536×1872（9 行），v2 1536×2288（11 行，多两行「看向」），
 * 单格恒为 192×208。硬编码 9 行时 v2 的行高会被算成 2288/9 = 254，
 * 每行多偏 46px、第 8 行累计偏 368px —— 动作错位并混入下一行内容。
 */
@RunWith(RobolectricTestRunner::class)
class SpritesheetGeometryTest {

    @Test
    fun `v1 sheet height implies 9 rows`() {
        assertEquals(9, CodexPetSpec.rowsForHeight(1872))
    }

    @Test
    fun `v2 sheet height implies 11 rows`() {
        assertEquals(11, CodexPetSpec.rowsForHeight(2288))
    }

    @Test
    fun `odd height falls back to v1 rows instead of crashing`() {
        assertEquals(9, CodexPetSpec.rowsForHeight(1873))
        assertEquals(9, CodexPetSpec.rowsForHeight(0))
        assertEquals(9, CodexPetSpec.rowsForHeight(-208))
    }

    @Test
    fun `cell size stays 192x208 for both versions`() {
        assertEquals(Pair(192, 208), SpritesheetParser.renderCellSize(1536, 1872))
        assertEquals(Pair(192, 208), SpritesheetParser.renderCellSize(1536, 2288))
    }

    @Test
    fun `cell size follows sheet width`() {
        assertEquals(Pair(128, 208), SpritesheetParser.renderCellSize(1024, 1872))
    }

    @Test
    fun `row 1 starts at 208px for v1`() {
        val r = SpritesheetParser().calculateCellRect(1, 0, 1536, 1872, 9)
        assertEquals(Rect(0, 208, 192, 416), r)
    }

    @Test
    fun `row 1 starts at 208px for v2 too`() {
        val r = SpritesheetParser().calculateCellRect(1, 0, 1536, 2288, 11)
        assertEquals(Rect(0, 208, 192, 416), r)
    }

    @Test
    fun `row 8 is identical on v1 and v2`() {
        val v1 = SpritesheetParser().calculateCellRect(8, 0, 1536, 1872, 9)
        val v2 = SpritesheetParser().calculateCellRect(8, 0, 1536, 2288, 11)
        assertEquals(Rect(0, 1664, 192, 1872), v1)
        assertEquals(v1, v2)
    }

    @Test
    fun `v2 look rows are addressable when rows are passed explicitly`() {
        val row10 = SpritesheetParser().calculateCellRect(10, 7, 1536, 2288, 11)
        assertEquals(Rect(1344, 2080, 1536, 2288), row10)
    }

    @Test
    fun `default rows argument derives from height`() {
        // 不传 rows 时也要按高度推：v2 的第 8 行必须落在 1664，而不是 9 行算法的同值巧合
        val auto = SpritesheetParser().calculateCellRect(10, 0, 1536, 2288)
        assertEquals(Rect(0, 2080, 192, 2288), auto)
    }
}
