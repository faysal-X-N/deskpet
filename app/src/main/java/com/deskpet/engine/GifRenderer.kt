package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.util.Log
import java.io.FileInputStream

class GifRenderer {

    private var movie: Movie? = null
    private var movieDuration: Int = 0
    private var lastBitmap: Bitmap? = null

    fun load(filePath: String): Boolean {
        return try {
            FileInputStream(filePath).use { input ->
                movie = Movie.decodeStream(input)
                movieDuration = movie?.duration() ?: 0
            }
            movie != null
        } catch (e: Exception) {
            Log.w("GifRenderer", "Failed to load GIF", e)
            false
        }
    }

    fun getFrame(): Bitmap? {
        val m = movie ?: return null
        if (movieDuration <= 0) return null

        val elapsed = (System.currentTimeMillis() % movieDuration).toInt()
        m.setTime(elapsed)

        val w = m.width().coerceAtLeast(1)
        val h = m.height().coerceAtLeast(1)

        // Reuse bitmap if dimensions match, otherwise create new
        val bitmap = lastBitmap?.takeIf { it.width == w && it.height == h }
            ?.also { it.eraseColor(android.graphics.Color.TRANSPARENT) }
            ?: run {
                lastBitmap?.recycle()
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { lastBitmap = it }
            }

        Canvas(bitmap).apply { m.draw(this, 0f, 0f) }
        return bitmap
    }

    val width: Int get() = movie?.width() ?: 0
    val height: Int get() = movie?.height() ?: 0
    val isLoaded: Boolean get() = movie != null

    fun release() {
        lastBitmap?.recycle()
        lastBitmap = null
        movie = null
    }
}
