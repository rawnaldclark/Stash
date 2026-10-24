package com.stash.feature.nowplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album art's gesture rules: when a swipe commits a skip (and which), how the art follows a
 * finger toward a side with nothing there, whether Next / Previous have anywhere to go, the
 * double-tap thirds, and how quick double-taps add up.
 */
class ArtGestureMathTest {

    private val width = 1_000f
    private val fling = 2_000f // px/s

    private fun outcome(
        offset: Float,
        velocity: Float = 0f,
        canNext: Boolean = true,
        canPrevious: Boolean = true,
        rtl: Boolean = false,
    ) = swipeSkipOutcome(offset, velocity, width, fling, canNext, canPrevious, rtl)

    @Test fun `a slow swipe past a third of the art skips, toward the side it was pulled`() {
        assertEquals(SwipeSkip.NEXT, outcome(offset = -300f))
        assertEquals(SwipeSkip.PREVIOUS, outcome(offset = 300f))
    }

    @Test fun `a slow swipe short of a third springs back`() {
        assertEquals(SwipeSkip.NONE, outcome(offset = -299f))
        assertEquals(SwipeSkip.NONE, outcome(offset = 250f, velocity = 500f))
    }

    @Test fun `a fling skips from a short travel, but a fast tap that slid does not`() {
        assertEquals(SwipeSkip.NEXT, outcome(offset = -100f, velocity = -2_500f))
        assertEquals(SwipeSkip.PREVIOUS, outcome(offset = 100f, velocity = 2_000f))
        assertEquals(SwipeSkip.NONE, outcome(offset = -50f, velocity = -4_000f))
    }

    @Test fun `flinging back the other way cancels even a long drag`() {
        assertEquals(SwipeSkip.NONE, outcome(offset = -600f, velocity = 2_500f))
    }

    @Test fun `a side with nothing there never commits`() {
        assertEquals(SwipeSkip.NONE, outcome(offset = -600f, canNext = false))
        assertEquals(SwipeSkip.NONE, outcome(offset = 600f, velocity = 3_000f, canPrevious = false))
        // The other side still works.
        assertEquals(SwipeSkip.PREVIOUS, outcome(offset = 600f, canNext = false))
    }

    @Test fun `right to left mirrors the direction`() {
        assertEquals(SwipeSkip.NEXT, outcome(offset = 400f, rtl = true))
        assertEquals(SwipeSkip.PREVIOUS, outcome(offset = -400f, rtl = true))
        assertTrue(isTowardNext(-1f, rtl = false))
        assertFalse(isTowardNext(-1f, rtl = true))
    }

    @Test fun `no travel or no width is never a skip`() {
        assertEquals(SwipeSkip.NONE, outcome(offset = 0f, velocity = -5_000f))
        assertEquals(SwipeSkip.NONE, swipeSkipOutcome(-500f, 0f, 0f, fling, true, true))
    }

    @Test fun `the art follows the finger fully toward a song and rubber-bands toward nothing`() {
        assertEquals(-200f, swipeVisualOffset(-200f, canNext = true, canPrevious = true), 0.001f)
        assertEquals(-200f * BLOCKED_SWIPE_RESISTANCE, swipeVisualOffset(-200f, canNext = false, canPrevious = true), 0.001f)
        assertEquals(200f * BLOCKED_SWIPE_RESISTANCE, swipeVisualOffset(200f, canNext = true, canPrevious = false), 0.001f)
        assertEquals(0f, swipeVisualOffset(0f, canNext = false, canPrevious = false), 0.001f)
    }

    @Test fun `next and previous follow the queue's ends, and repeat-all wraps them`() {
        assertTrue(hasNextInQueue(queueSize = 3, currentIndex = 1, repeatAll = false))
        assertFalse(hasNextInQueue(queueSize = 3, currentIndex = 2, repeatAll = false))
        assertTrue(hasNextInQueue(queueSize = 3, currentIndex = 2, repeatAll = true))
        assertFalse(hasPreviousInQueue(queueSize = 3, currentIndex = 0, repeatAll = false))
        assertTrue(hasPreviousInQueue(queueSize = 3, currentIndex = 0, repeatAll = true))
        assertFalse(hasNextInQueue(queueSize = 0, currentIndex = 0, repeatAll = true))
        assertFalse(hasPreviousInQueue(queueSize = 0, currentIndex = 0, repeatAll = true))
    }

    @Test fun `double-taps seek back on the left third, forward on the right, nothing in the middle`() {
        assertEquals(-1, doubleTapSeekDirection(100f, width))
        assertEquals(0, doubleTapSeekDirection(500f, width))
        assertEquals(1, doubleTapSeekDirection(900f, width))
        assertEquals(0, doubleTapSeekDirection(100f, 0f))
    }

    @Test fun `quick double-taps the same way add up from the last target`() {
        val first = nextSeekBurst(previous = null, direction = 1, nowMs = 1_000L, positionMs = 30_000L, durationMs = 180_000L)
        assertEquals(40_000L, first.targetMs)
        assertEquals("+10 s", seekBurstLabel(first))
        // The player still reports 30 s: the second tap builds on the first one's target.
        val second = nextSeekBurst(first, direction = 1, nowMs = 1_500L, positionMs = 30_000L, durationMs = 180_000L)
        assertEquals(50_000L, second.targetMs)
        assertEquals("+20 s", seekBurstLabel(second))
    }

    @Test fun `a late tap or a change of direction starts a new burst from the position`() {
        val first = nextSeekBurst(null, direction = 1, nowMs = 1_000L, positionMs = 30_000L, durationMs = 180_000L)
        val late = nextSeekBurst(first, direction = 1, nowMs = 1_000L + SEEK_BURST_WINDOW_MS + 1, positionMs = 41_000L, durationMs = 180_000L)
        assertEquals(51_000L, late.targetMs)
        assertEquals(SEEK_STEP_MS, late.totalMs)
        val back = nextSeekBurst(first, direction = -1, nowMs = 1_200L, positionMs = 40_000L, durationMs = 180_000L)
        assertEquals(30_000L, back.targetMs)
        assertEquals("−10 s", seekBurstLabel(back))
    }

    @Test fun `a burst stays inside the song`() {
        val start = nextSeekBurst(null, direction = -1, nowMs = 0L, positionMs = 4_000L, durationMs = 180_000L)
        assertEquals(0L, start.targetMs)
        val end = nextSeekBurst(null, direction = 1, nowMs = 0L, positionMs = 175_000L, durationMs = 180_000L)
        assertEquals(180_000L, end.targetMs)
    }

    @Test fun `a tap off the seek thirds flips at once, on one waits for a double-tap`() {
        assertTrue(flipsOnFirstTap(canFlip = true, seeks = true, xPx = 500f, widthPx = 900f))
        assertFalse(flipsOnFirstTap(canFlip = true, seeks = true, xPx = 100f, widthPx = 900f))
        assertFalse(flipsOnFirstTap(canFlip = true, seeks = true, xPx = 800f, widthPx = 900f))
    }

    @Test fun `with no seeking every tap flips at once`() {
        assertTrue(flipsOnFirstTap(canFlip = true, seeks = false, xPx = 100f, widthPx = 900f))
        assertTrue(flipsOnFirstTap(canFlip = true, seeks = false, xPx = 800f, widthPx = 900f))
    }

    @Test fun `an album with no back never flips`() {
        assertFalse(flipsOnFirstTap(canFlip = false, seeks = false, xPx = 450f, widthPx = 900f))
        assertFalse(flipsOnFirstTap(canFlip = false, seeks = true, xPx = 450f, widthPx = 900f))
        assertFalse(loneSeekTapFlips(canFlip = false, burst = null, nowMs = 0L))
    }

    @Test fun `a lone tap on a seek third flips, unless it follows a seek run`() {
        assertTrue(loneSeekTapFlips(canFlip = true, burst = null, nowMs = 5_000L))
        val run = nextSeekBurst(null, direction = 1, nowMs = 5_000L, positionMs = 30_000L, durationMs = 180_000L)
        assertFalse(loneSeekTapFlips(canFlip = true, burst = run, nowMs = 5_000L + SEEK_BURST_WINDOW_MS))
        assertTrue(loneSeekTapFlips(canFlip = true, burst = run, nowMs = 5_000L + SEEK_BURST_WINDOW_MS + 1))
    }
}
