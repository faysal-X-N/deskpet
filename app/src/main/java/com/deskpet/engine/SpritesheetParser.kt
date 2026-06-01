package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import com.deskpet.data.model.AnimationState
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.PetAnimationState
import kotlinx.serialization.json.Json

class SpritesheetParser {

    companion object {
        const val SPRITESHEET_COLS = 8
        const val SPRITESHEET_ROWS = 9
        const val DEFAULT_FRAME_COUNT = 6
        private const val TAG = "SpritesheetParser"
    }

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cachedDecoder: BitmapRegionDecoder? = null
    @Volatile private var cachedFullBitmap: Bitmap? = null
    @Volatile private var cachedPath: String? = null

    fun parse(petJsonPath: String): CodexPetConfig? {
        return try {
            val content = java.io.File(petJsonPath).readText()
            json.decodeFromString<CodexPetConfig>(content)
        } catch (e: Exception) { Log.w(TAG, "Failed to parse pet.json: $petJsonPath", e); null }
    }

    private fun getStateConfig(config: CodexPetConfig, state: PetAnimationState): AnimationState {
        return config.states
            ?.find { it.name.equals(state.specName, ignoreCase = true) }
            ?: PetAnimationState.DEFAULT_STATE_MAP[state.specName]
            ?: AnimationState(state.specName, state.row, DEFAULT_FRAME_COUNT)
    }

    private fun ensureCache(spritesheetPath: String) {
        if (cachedPath != spritesheetPath) {
            cachedDecoder?.recycle()
            cachedFullBitmap?.recycle()
            cachedDecoder = null
            cachedFullBitmap = null
            cachedPath = spritesheetPath
        }
    }

    private fun getDecoder(spritesheetPath: String): BitmapRegionDecoder? {
        ensureCache(spritesheetPath)
        if (cachedDecoder == null || cachedDecoder?.isRecycled == true) {
            cachedDecoder = try {
                BitmapRegionDecoder.newInstance(spritesheetPath, false)
            } catch (e: Exception) { Log.w(TAG, "Failed to create RegionDecoder", e); null }
        }
        return if (cachedDecoder?.isRecycled == false) cachedDecoder else null
    }

    private fun getFullBitmap(spritesheetPath: String): Bitmap? {
        ensureCache(spritesheetPath)
        if (cachedFullBitmap == null || cachedFullBitmap?.isRecycled == true) {
            cachedFullBitmap = try {
                BitmapFactory.decodeFile(spritesheetPath)
            } catch (e: Exception) { Log.w(TAG, "Failed to decode full bitmap", e); null }
        }
        return cachedFullBitmap
    }

    fun extractFrame(
        config: CodexPetConfig,
        spritesheetPath: String,
        state: PetAnimationState,
        frameIndex: Int
    ): Bitmap? {
        val stateConfig = getStateConfig(config, state)
        val frames = stateConfig.frames
        if (frames <= 0) return null
        val col = frameIndex % frames

        // API 28+: BitmapRegionDecoder
        val decoder = getDecoder(spritesheetPath)
        if (decoder != null) {
            val rect = calculateCellRect(stateConfig.row, col, decoder.width, decoder.height)
            return try {
                decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.HARDWARE
                })
            } catch (e: Exception) { Log.w(TAG, "Failed to decode region", e); null }
        }

        // API 26-27 WebP 回退
        val full = getFullBitmap(spritesheetPath) ?: return null
        val rect = calculateCellRect(stateConfig.row, col, full.width, full.height)
        return try {
            Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
        } catch (e: Exception) { Log.w(TAG, "Failed to createBitmap from full", e); null }
    }

    fun getFrameCount(config: CodexPetConfig, state: PetAnimationState): Int {
        return getStateConfig(config, state).frames
    }

    fun calculateCellRect(row: Int, col: Int, w: Int, h: Int): Rect {
        val cw = w / SPRITESHEET_COLS
        val ch = h / SPRITESHEET_ROWS
        return Rect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch)
    }

    fun release() {
        cachedDecoder?.recycle()
        cachedDecoder = null
        cachedFullBitmap?.recycle()
        cachedFullBitmap = null
        cachedPath = null
    }
}
