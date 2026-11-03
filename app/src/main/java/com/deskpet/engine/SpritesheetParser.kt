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

    /**
     * Codex 帧双缓冲（与 GifRenderer 同款设计）：decodeRegion 每帧新分配约 160KB
     * 位图，2~5FPS 下每秒 0.3~0.8MB 的 GC 压力。用 inBitmap 让 A/B 两块缓冲交替
     * 复用——第 N 帧写 A 时屏幕显示的是 N-1 帧的 B（上次绘制在 2 帧之前），
     * 实际不会读写竞争。稳态零分配。
     */
    private val frameBuffers = arrayOfNulls<Bitmap>(2)
    private var fbIdx = 0

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

    @Synchronized
    fun extractFrame(
        config: CodexPetConfig,
        spritesheetPath: String,
        state: PetAnimationState,
        frameIndex: Int
    ): Bitmap? {
        val stateConfig = getStateConfig(config, state)
        val frames = stateConfig.frames
        if (frames <= 0) return null
        // 行号/列号钳制：pet.json 是外部导入的数据，frames=50 / row=100 这类
        // 越界声明不能让它去解码 8x9 网格之外的区域（错帧甚至越界崩溃）
        val row = stateConfig.row.coerceIn(0, SPRITESHEET_ROWS - 1)
        val col = frameIndex % frames

        // API 28+: BitmapRegionDecoder
        val decoder = getDecoder(spritesheetPath)
        if (decoder != null) {
            val rect = calculateCellRect(row, col, decoder.width, decoder.height)
            return try {
                // 双缓冲复用（inBitmap）：帧位图必须是 mutable。首帧自建可变缓冲，
                // 之后 A/B 交替写入；inBitmap 复用失败时退回普通解码保证正确性。
                fbIdx = (fbIdx + 1) % frameBuffers.size
                val w = rect.width()
                val h = rect.height()
                val existing = frameBuffers[fbIdx]
                var bmp: Bitmap? = null
                if (existing != null && !existing.isRecycled && existing.isMutable &&
                    existing.width == w && existing.height == h
                ) {
                    bmp = try {
                        decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inMutable = true
                            inBitmap = existing
                        })
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                if (bmp == null) {
                    val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp = try {
                        decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inMutable = true
                            inBitmap = fresh
                        })
                    } catch (e: IllegalArgumentException) {
                        // 连自建缓冲都复用失败（系统不支持等）：普通解码，本帧不复用
                        decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })
                    }
                }
                frameBuffers[fbIdx] = bmp
                bmp
            } catch (e: Exception) { Log.w(TAG, "Failed to decode region", e); null }
        }

        // API 26-27 WebP 回退
        val full = getFullBitmap(spritesheetPath) ?: return null
        val rect = calculateCellRect(row, col, full.width, full.height)
        return try {
            Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
        } catch (e: Exception) { Log.w(TAG, "Failed to createBitmap from full", e); null }
    }

    fun getFrameCount(config: CodexPetConfig, state: PetAnimationState): Int {
        // 上限 = 一行最多 8 列：声明再多帧也取不到，反而会让列号越界
        return getStateConfig(config, state).frames.coerceIn(0, SPRITESHEET_COLS)
    }

    fun calculateCellRect(row: Int, col: Int, w: Int, h: Int): Rect {
        val cw = w / SPRITESHEET_COLS
        val ch = h / SPRITESHEET_ROWS
        return Rect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch)
    }

    /**
     * 释放缓存。与 [extractFrame] 互斥：解码可能在 IO 线程进行中，
     * 直接 recycle 会踩到 "trying to use a recycled bitmap"。
     * 帧缓冲与 GIF 双缓冲同理：只清引用不 recycle，交给 GC。
     */
    @Synchronized
    fun release() {
        cachedDecoder?.recycle()
        cachedDecoder = null
        cachedFullBitmap?.recycle()
        cachedFullBitmap = null
        cachedPath = null
        frameBuffers.fill(null)
        fbIdx = 0
    }
}
