package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class GifRendererTest {

    private val renderer = GifRenderer()

    @Test
    fun `isLoaded is false initially`() {
        assertFalse(renderer.isLoaded)
    }

    @Test
    fun `width returns 0 when not loaded`() {
        assertEquals(0, renderer.width)
    }

    @Test
    fun `height returns 0 when not loaded`() {
        assertEquals(0, renderer.height)
    }

    @Test
    fun `getFrame returns null when not loaded`() {
        assertNull(renderer.getFrame())
    }

    @Test
    fun `release without load does not crash`() {
        renderer.release()
    }

    @Test
    fun `release then getFrame returns null`() {
        renderer.release()
        assertNull(renderer.getFrame())
    }

    @Test
    fun `isLoaded false after release`() {
        renderer.release()
        assertFalse(renderer.isLoaded)
    }
}
