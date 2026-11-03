package com.stash.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The drag's arithmetic: the queue's edge scroll (issue #319) and the half-a-neighbour swap rule. */
class DragToReorderTest {

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

    @Test
    fun `rows of one height swap past half a row, the queue's rule`() {
        val rows = listOf(100, 100, 100)
        assertEquals(0, heldRowSwap(1, 49f, rows))
        assertEquals(1, heldRowSwap(1, 51f, rows))
        assertEquals(0, heldRowSwap(1, -49f, rows))
        assertEquals(-1, heldRowSwap(1, -51f, rows))
    }

    @Test
    fun `it takes half of the neighbour, not of the held row`() {
        // A short row held over a tall one: past half the tall one
        val rows = listOf(40, 120, 40)
        assertEquals(0, heldRowSwap(0, 59f, rows))
        assertEquals(1, heldRowSwap(0, 61f, rows))
        // A tall row held under a short one: past half the short one
        assertEquals(-1, heldRowSwap(1, -21f, rows))
        assertEquals(0, heldRowSwap(1, -19f, rows))
    }

    @Test
    fun `after a swap the row stays put until it's dragged back over the same half`() {
        // The held 40px row 70px down, over a 120px row: it swaps, and its offset drops by that row's height.
        val before = listOf(40, 120, 40)
        assertEquals(1, heldRowSwap(0, 70f, before))
        val after = listOf(120, 40, 40)
        val offset = 70f - 120f
        assertEquals(0, heldRowSwap(1, offset, after))
        // Back up past the tall row's middle (where it swapped down), it swaps back
        assertEquals(0, heldRowSwap(1, offset - 9f, after))
        assertEquals(-1, heldRowSwap(1, offset - 11f, after))
    }

    @Test
    fun `the held row is drawn inside the column, however far the finger goes`() {
        val rows = listOf(40, 60, 40)
        // The middle row: 40 above it, 40 below it
        assertEquals(25f, heldRowOffsetInColumn(1, 25f, rows), 0f)
        assertEquals(40f, heldRowOffsetInColumn(1, 300f, rows), 0f)
        assertEquals(-40f, heldRowOffsetInColumn(1, -300f, rows), 0f)
        // The first row can't go above the top, the last below the bottom
        assertEquals(0f, heldRowOffsetInColumn(0, -10f, rows), 0f)
        assertEquals(0f, heldRowOffsetInColumn(2, 10f, rows), 0f)
        assertEquals(-100f, heldRowOffsetInColumn(2, -500f, rows), 0f)
    }

    @Test
    fun `the edge scroll goes only as far as the list's end`() {
        // A list from 100 to 900 on screen, in a viewport 0..500: 400 of it hidden below, 100 above
        assertEquals(6f, edgeScrollWithinList(6f, listTop = -100f, listBottom = 900f, viewTop = 0f, viewBottom = 500f), 0f)
        assertEquals(-6f, edgeScrollWithinList(-6f, listTop = -100f, listBottom = 900f, viewTop = 0f, viewBottom = 500f), 0f)
        // 2px of it left below: 2px, then nothing, though the page goes on
        assertEquals(2f, edgeScrollWithinList(6f, listTop = -398f, listBottom = 502f, viewTop = 0f, viewBottom = 500f), 0f)
        assertEquals(0f, edgeScrollWithinList(6f, listTop = -400f, listBottom = 500f, viewTop = 0f, viewBottom = 500f), 0f)
        assertEquals(0f, edgeScrollWithinList(6f, listTop = -420f, listBottom = 480f, viewTop = 0f, viewBottom = 500f), 0f)
        // Its top in view: nothing up
        assertEquals(0f, edgeScrollWithinList(-6f, listTop = 20f, listBottom = 900f, viewTop = 0f, viewBottom = 500f), 0f)
        assertEquals(-3f, edgeScrollWithinList(-6f, listTop = -3f, listBottom = 900f, viewTop = 0f, viewBottom = 500f), 0f)
        assertEquals(0f, edgeScrollWithinList(0f, listTop = -100f, listBottom = 900f, viewTop = 0f, viewBottom = 500f), 0f)
    }

    @Test
    fun `nothing swaps past the ends or before the held row is measured`() {
        assertEquals(0, heldRowSwap(0, -1000f, listOf(50, 50)))
        assertEquals(0, heldRowSwap(1, 1000f, listOf(50, 50)))
        assertEquals(0, heldRowSwap(0, 1000f, listOf(0, 50)))
        assertEquals(0, heldRowSwap(5, 1000f, listOf(50, 50)))
    }
}
