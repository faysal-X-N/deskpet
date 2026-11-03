package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.FileInputStream

/**
 * GIF 帧解码。
 *
 * 帧数据通过 `MutableStateFlow` 下发给 Compose：
 * - 每帧都要发射"新值"才会触发重组——由 [AnimationController.Frame] 的递增序号保证；
 * - 位图本身用**双缓冲**交替复用（A/B 两块），避免每帧新建约 1MB 的 Bitmap 给 GC 施压。
 *   第 N 帧写缓冲 A 时，屏幕上显示的是第 N-1 帧的缓冲 B；A 上上次被绘制是 2 帧之前
 *   （约 400ms），渲染早已完成，实际不会出现读写竞争。
 */
@Suppress("DEPRECATION")
class GifRenderer {

    private var movie: android.graphics.Movie? = null
    private var movieDuration: Int = 0
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var bufIdx = 0

    /** 加载新 GIF。与 getFrame/release 互斥：切换宠物时 load 发生在 IO 线程，
     *  而动画循环可能在另一 IO 线程并发调 getFrame，不互斥会读到半初始化状态。 */
    @Synchronized
    fun load(filePath: String): Boolean {
        release()
        return try {
            FileInputStream(filePath).use { input ->
                movie = android.graphics.Movie.decodeStream(input)
                movieDuration = movie?.duration() ?: 0
            }
            movie != null
        } catch (e: Exception) {
            Log.w("GifRenderer", "Failed to load GIF", e)
            movie = null
            movieDuration = 0
            false
        }
    }

    @Synchronized
    fun getFrame(): Bitmap? {
        val m = movie ?: return null
        if (movieDuration <= 0) return null

        val elapsed = (System.currentTimeMillis() % movieDuration).toInt()
        m.setTime(elapsed)

        val w = m.width().coerceAtLeast(1)
        val h = m.height().coerceAtLeast(1)

        bufIdx = (bufIdx + 1) % buffers.size
        val bitmap = buffers[bufIdx]?.takeIf { !it.isRecycled && it.width == w && it.height == h }
            ?: createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffers[bufIdx] = it }
        // 清掉上一帧的残影（Movie.draw 不会填满透明区域）
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        Canvas(bitmap).apply { m.draw(this, 0f, 0f) }
        return bitmap
    }

    val width: Int get() = movie?.width() ?: 0
    val height: Int get() = movie?.height() ?: 0
    val isLoaded: Boolean get() = movie != null

    @Synchronized
    fun release() {
        movie = null
        movieDuration = 0
        // 注意：不 recycle 缓冲位图。切换宠物时旧 ComposeView 可能仍在渲染最后一帧，
        // recycle 会引发 "Cannot draw a recycled Bitmap" 崩溃；只清引用交给 GC 回收
        buffers.fill(null)
        bufIdx = 0
    }
}
