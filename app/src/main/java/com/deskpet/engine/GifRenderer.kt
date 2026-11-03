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
 * - 位图本身用**双缓冲**交替复用（A/B 两块），避免每帧新建大 Bitmap 给 GC 施压。
 *   第 N 帧写缓冲 A 时，屏幕上显示的是第 N-1 帧的缓冲 B；A 上上次被绘制是 2 帧之前
 *   （约 400ms），渲染早已完成，实际不会出现读写竞争。
 *
 * 两道极端输入防线（2026-11-04 审查补充）：
 * - **单帧静态 GIF**：duration() 为 0，旧实现直接返回 null 导致一帧都发不出、
 *   宠物永远不显示；现在按第 0 帧渲染。
 * - **超大 GIF**：不限制源文件尺寸的话，4000x4000 的 GIF 要开 128MB 双缓冲
 *   且悬浮窗宽出屏幕；现在渲染尺寸封顶 [MAX_RENDER_DIM]，超限时等比降采样
 *   （悬浮窗实际显示尺寸远小于 1024px，画质无损）。
 */
@Suppress("DEPRECATION")
class GifRenderer {

    private var movie: android.graphics.Movie? = null
    private var movieDuration: Int = 0
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var bufIdx = 0

    /** 渲染尺寸（降采样后）：窗口尺寸与缓冲都以它为准 */
    @Volatile private var renderW = 0
    @Volatile private var renderH = 0

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
            recalcRenderSize()
            movie != null
        } catch (e: Exception) {
            Log.w("GifRenderer", "Failed to load GIF", e)
            movie = null
            movieDuration = 0
            false
        }
    }

    /** 等比降采样：最长边封顶 [MAX_RENDER_DIM]，短边至少 1px */
    private fun recalcRenderSize() {
        val m = movie
        if (m == null || m.width() <= 0 || m.height() <= 0) {
            renderW = 0; renderH = 0
            return
        }
        val w = m.width(); val h = m.height()
        val k = minOf(1f, MAX_RENDER_DIM.toFloat() / maxOf(w, h))
        renderW = (w * k).toInt().coerceAtLeast(1)
        renderH = (h * k).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun getFrame(): Bitmap? {
        val m = movie ?: return null
        if (renderW <= 0 || renderH <= 0) return null

        // 动态 GIF 按循环进度取帧；静态 GIF（duration=0）固定第 0 帧——
        // 旧实现在这里直接返回 null，宠物会一片空白永远不显示
        if (movieDuration > 0) {
            m.setTime((System.currentTimeMillis() % movieDuration).toInt())
        } else {
            m.setTime(0)
        }

        bufIdx = (bufIdx + 1) % buffers.size
        val bitmap = buffers[bufIdx]?.takeIf { !it.isRecycled && it.width == renderW && it.height == renderH }
            ?: createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888).also { buffers[bufIdx] = it }
        // 清掉上一帧的残影（Movie.draw 不会填满透明区域）
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        // 源尺寸超限时等比缩到渲染尺寸（k <= 1，放大场景不存在）
        if (renderW < m.width() || renderH < m.height()) {
            canvas.scale(renderW.toFloat() / m.width(), renderH.toFloat() / m.height())
        }
        m.draw(canvas, 0f, 0f)
        return bitmap
    }

    val width: Int get() = renderW
    val height: Int get() = renderH
    val isLoaded: Boolean get() = movie != null

    @Synchronized
    fun release() {
        movie = null
        movieDuration = 0
        renderW = 0
        renderH = 0
        // 注意：不 recycle 缓冲位图。切换宠物时旧 ComposeView 可能仍在渲染最后一帧，
        // recycle 会引发 "Cannot draw a recycled Bitmap" 崩溃；只清引用交给 GC 回收
        buffers.fill(null)
        bufIdx = 0
    }

    companion object {
        /** 渲染最长边封顶 1024px：双缓冲最多 2x8MB，任何设备都安全 */
        private const val MAX_RENDER_DIM = 1024
    }
}
