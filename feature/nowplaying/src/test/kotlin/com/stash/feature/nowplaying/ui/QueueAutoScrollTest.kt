package com.stash.feature.nowplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #319: edge auto-scroll velocity for queue drag-to-reorder. */
class QueueAutoScrollTest {

    // Viewport 1000px, edge zones 100px, max step 10px/frame.
    private fun step(top: Float, bottom: Float) =
        autoScrollStep(top, bottom, viewportPx = 1000f, edgePx = 100f, maxStepPx = 10f)

    @Test
    fun `mid-viewport does not scroll`() {
        assertEquals(0f, step(400f, 480f), 0f)
    }

    @Test
    fun `top zone scrolls toward the start, ramping with depth`() {
        assertEquals(-5f, step(50f, 130f), 0.01f)   // halfway in
        assertEquals(-10f, step(0f, 80f), 0.01f)    // fully in
        assertEquals(-10f, step(-40f, 40f), 0.01f)  // beyond: clamped
    }

    @Test
    fun `bottom zone scrolls toward the end, ramping with depth`() {
        assertEquals(5f, step(870f, 950f), 0.01f)   // halfway in
        assertEquals(10f, step(920f, 1000f), 0.01f) // fully in
        assertEquals(10f, step(960f, 1040f), 0.01f) // beyond: clamped
    }

    @Test
    fun `degenerate geometry is safe`() {
        assertEquals(0f, autoScrollStep(0f, 50f, viewportPx = 0f, edgePx = 100f, maxStepPx = 10f), 0f)
        assertEquals(0f, autoScrollStep(0f, 50f, viewportPx = 1000f, edgePx = 0f, maxStepPx = 10f), 0f)
    }
}
