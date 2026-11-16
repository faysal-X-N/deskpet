package com.deskpet.engine

/**
 * Codex Pet 图集规格的**唯一真源**（single source of truth）。
 *
 * 之前这套数字散在四个文件里：`PetEngine`（8/9/192/208）、`SpritesheetParser`（8/9/208/11）、
 * `PetListScreen`（8/9 各两处）、`PetFrameTiming`（帧数）。改规范要改四处，漏一处就是
 * v2 素材在某个界面切歪——历史上"列表缩略图仍按 9 行"就是这么漏的。
 *
 * 规格（实测社区 384 只素材）：
 * - 图集固定 **8 列**，单格 **192×208**；
 * - v1 = 9 行（1536×1872）：idle / running-right / running-left / waving / jumping /
 *   failed / waiting / running / review；
 * - v2 = 11 行（1536×2288）：前 9 行同上，多两行「看向」（000–157° / 180–337°）；
 * - 行数由图片高度推出，不读 `spriteVersionNumber`（转码/第三方打包不一定同步该字段）。
 */
object CodexPetSpec {

    /** 图集列数（两种版本相同）。 */
    const val COLS = 8

    /** 单格宽（像素）。 */
    const val CELL_W = 192

    /** 单格高（像素）。也是"由高度推行数"的除数。 */
    const val CELL_H = 208

    /** v1 行数；也是高度对不上规范时的回落值。 */
    const val DEFAULT_ROWS = 9

    /** 目前规范里最多的行数（v2 = 11 行）。超过即视为畸形素材。 */
    const val MAX_ROWS = 11

    /**
     * 由图集高度推行数：1872 → 9、2288 → 11。
     * 高度不是 [CELL_H] 的整数倍（畸形/被裁过/被降采样过）时回落 [DEFAULT_ROWS]。
     */
    fun rowsForHeight(sheetH: Int): Int {
        if (sheetH <= 0) return DEFAULT_ROWS
        val rows = sheetH / CELL_H
        return if (sheetH % CELL_H == 0 && rows in 1..MAX_ROWS) rows else DEFAULT_ROWS
    }
}
