package com.deskpet.engine

import android.graphics.Bitmap
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.PetAnimationState
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType

class PetEngine(
    private val spritesheetParser: SpritesheetParser,
    private val gifRenderer: GifRenderer
) {

    sealed class PetLoader {
        data class CodexPet(
            val config: CodexPetConfig,
            val spritesheetPath: String
        ) : PetLoader()

        data class Gif(val filePath: String) : PetLoader()
    }

    fun createLoader(petInfo: PetInfo): PetLoader? {
        return when (petInfo.type) {
            PetType.CODEX_PET -> {
                val config = petInfo.petJsonPath?.let {
                    spritesheetParser.parse(it)
                } ?: return null

                PetLoader.CodexPet(config, petInfo.spritesheetPath)
            }
            PetType.GIF -> {
                if (gifRenderer.load(petInfo.spritesheetPath)) {
                    PetLoader.Gif(petInfo.spritesheetPath)
                } else {
                    null
                }
            }
        }
    }

    fun getCurrentFrame(
        loader: PetLoader,
        state: PetAnimationState,
        frameIndex: Int
    ): Bitmap? {
        return when (loader) {
            is PetLoader.CodexPet -> {
                spritesheetParser.extractFrame(
                    loader.config,
                    loader.spritesheetPath,
                    state,
                    frameIndex
                )
            }
            is PetLoader.Gif -> {
                gifRenderer.getFrame()
            }
        }
    }

    fun getFrameCount(loader: PetLoader, state: PetAnimationState): Int {
        return when (loader) {
            is PetLoader.CodexPet -> spritesheetParser.getFrameCount(loader.config, state)
            is PetLoader.Gif -> 1  // GIF 不分帧，每帧独立
        }
    }

    fun getPetSize(loader: PetLoader): Pair<Int, Int> {
        return when (loader) {
            is PetLoader.CodexPet -> {
                // 图集固定 8 列，行数由高度决定（v1=9 / v2=11），
                // 单元格尺寸统一走 SpritesheetParser.renderCellSize
                // But we need to read actual dimensions
                val spritesheet = java.io.File(loader.spritesheetPath)
                if (!spritesheet.exists()) return Pair(CodexPetSpec.CELL_W, CodexPetSpec.CELL_H)
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(loader.spritesheetPath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return Pair(CodexPetSpec.CELL_W, CodexPetSpec.CELL_H)
                }
                // 与 extractFrame 的渲染尺寸保持一致（超大单帧会被降采样，
                // 窗口若按原始单元格算会比宠物画面大一圈）
                val (w, h) = SpritesheetParser.renderCellSize(options.outWidth, options.outHeight)
                if (w <= 0 || h <= 0) Pair(CodexPetSpec.CELL_W, CodexPetSpec.CELL_H) else Pair(w, h)
            }
            is PetLoader.Gif -> Pair(gifRenderer.width, gifRenderer.height)
        }
    }

    /** 释放解码缓存（服务销毁时调用）*/
    fun release() {
        spritesheetParser.release()
        gifRenderer.release()
    }
}
