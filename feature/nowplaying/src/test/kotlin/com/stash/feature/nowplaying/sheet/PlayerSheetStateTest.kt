package com.stash.feature.nowplaying.sheet

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetStateTest {

    /** A 60 Hz frame clock on the test scheduler's virtual time. */
    private class TestFrameClock : MonotonicFrameClock {
        private var frameNanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16)
            frameNanos += 16_000_000L
            return onFrame(frameNanos)
        }
    }

    private val density = 2.75f

    /** Lets a snap (300 ms plus a frame or two) finish. advanceUntilIdle ignores backgroundScope work. */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    /** A sheet on a Pixel 5 sized screen, its mini player card where the scaffold puts it. */
    private fun TestScope.sheet(
        initial: PlayerSheetAnchor = PlayerSheetAnchor.MINI,
        keepWarmMs: Long = KEEP_WARM_MS,
    ): PlayerSheetState {
        val scope = CoroutineScope(backgroundScope.coroutineContext + TestFrameClock())
        return PlayerSheetState(initial, scope, keepWarmMs).apply {
            this.density = this@PlayerSheetStateTest.density
            screenSize = IntSize(1080, 2340)
            miniCardRect = Rect(22f, 1870f, 1058f, 2035f)
        }
    }

    @Test
    fun `starts parked at MINI, cold`() = runTest {
        val s = sheet()
        assertEquals(0f, s.progress, 0f)
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)
        assertFalse(s.isWarm)
        assertTrue(s.isAtRest(PlayerSheetAnchor.MINI))
    }

    @Test
    fun `snapTo jumps without animating and warms for FULL`() = runTest {
        val s = sheet()
        s.snapTo(PlayerSheetAnchor.FULL)
        assertEquals(1f, s.progress, 0f)
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
        assertTrue(s.isWarm)
    }

    @Test
    fun `expand animates to FULL in about 300 ms and settles only at the end`() = runTest {
        val s = sheet()
        s.expand()
        assertTrue(s.isWarm)
        assertEquals(PlayerSheetAnchor.FULL, s.targetAnchor)
        advanceTimeBy(150)
        runCurrent()
        assertTrue("mid-flight: ${s.progress}", s.progress > 0f && s.progress < 1f)
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)
        settle()
        assertEquals(1f, s.progress, 0f)
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
    }

    @Test
    fun `reduced motion snaps`() = runTest {
        val s = sheet()
        s.reducedMotion = true
        s.expand()
        advanceTimeBy(40) // the cold player's one frame to compose, then no animation
        runCurrent()
        assertEquals(1f, s.progress, 0f)
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
    }

    @Test
    fun `a drag remembers where it started and settles by distance or flick`() = runTest {
        val s = sheet()
        val travel = s.travelPx() // 1870 px: the card's top travels to the screen's
        assertEquals(1870f, travel, 0f)

        // Up 200 dp, released slowly: past the 100 dp actuation, opens.
        s.beginDrag()
        assertTrue(s.isDragging)
        s.dragBy(200f * density)
        assertEquals(200f * density / travel, s.progress, 1e-5f)
        s.endDrag(velocityUpDpS = 50f)
        settle()
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)

        // Down 60 dp from FULL, slowly: not enough, back to FULL.
        s.beginDrag()
        assertEquals(PlayerSheetAnchor.FULL, s.targetAnchor)
        s.dragBy(-60f * density)
        s.endDrag(velocityUpDpS = -100f)
        settle()
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)

        // Down 20 dp but flicked: closes.
        s.beginDrag()
        s.dragBy(-20f * density)
        s.endDrag(velocityUpDpS = -900f)
        settle()
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)
        assertEquals(0f, s.progress, 0f)
    }

    @Test
    fun `a short slow drag from MINI returns to MINI`() = runTest {
        val s = sheet()
        s.beginDrag()
        s.dragBy(80f * density)
        s.endDrag(velocityUpDpS = 100f)
        settle()
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)
        assertEquals(0f, s.progress, 0f)
    }

    @Test
    fun `dragging past either end rubber-bands and never passes the limits`() = runTest {
        val s = sheet()
        s.beginDrag()
        s.dragBy(-150f)
        assertTrue(s.progress < 0f && s.progress > PlayerSheetMath.MIN_PROGRESS)
        s.endDrag(0f)
        settle()
        assertEquals(0f, s.progress, 0f)

        s.snapTo(PlayerSheetAnchor.FULL)
        s.beginDrag()
        s.dragBy(200f)
        assertTrue(s.progress > 1f && s.progress < PlayerSheetMath.MAX_PROGRESS_PHASE_A)
        s.endDrag(2000f) // even a hard flick up: no queue in phase A
        settle()
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
        assertEquals(1f, s.progress, 0f)
    }

    @Test
    fun `a touch during a snap catches the sheet where it is`() = runTest {
        val s = sheet()
        s.snapTo(PlayerSheetAnchor.FULL)
        s.collapse()
        advanceTimeBy(100)
        runCurrent()
        val caught = s.progress
        assertTrue(caught > 0f && caught < 1f)
        s.beginDrag()
        advanceTimeBy(200)
        runCurrent()
        assertEquals("the snap stopped", caught, s.progress, 0f)
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor) // the cancelled snap never settled
        s.endDrag(900f) // flick back up
        settle()
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
    }

    @Test
    fun `a haptic ticks only when a drag past the actuation distance changes anchor`() = runTest {
        val s = sheet()
        var ticks = 0
        s.onSnapHaptic = { ticks++ }
        s.beginDrag(); s.dragBy(50f * density); s.endDrag(0f); settle()
        assertEquals(0, ticks)
        s.beginDrag(); s.dragBy(300f * density); s.endDrag(0f); settle()
        assertEquals(1, ticks)
        s.expand(); settle() // a tap: no tick
        assertEquals(1, ticks)
    }

    @Test
    fun `Back asks the newest consumer first, then collapses, and is not ours at MINI`() = runTest {
        val s = sheet()
        assertFalse(s.handleBack())

        s.snapTo(PlayerSheetAnchor.FULL)
        val calls = mutableListOf<String>()
        var lyricsOpen = true
        val unregisterOld = s.registerBackConsumer { calls += "old"; false }
        val unregisterLyrics = s.registerBackConsumer {
            calls += "lyrics"
            if (lyricsOpen) { lyricsOpen = false; true } else false
        }
        assertTrue(s.handleBack())
        assertEquals(listOf("lyrics"), calls)
        assertEquals(PlayerSheetAnchor.FULL, s.targetAnchor) // still open: Back only left lyrics

        calls.clear()
        assertTrue(s.handleBack())
        assertEquals(listOf("lyrics", "old"), calls)
        assertEquals(PlayerSheetAnchor.MINI, s.targetAnchor)
        settle()
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)

        unregisterLyrics(); unregisterOld()
        calls.clear()
        s.snapTo(PlayerSheetAnchor.FULL)
        assertTrue(s.handleBack())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the saver restores the anchor the sheet was going to, without animating`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + TestFrameClock())
        val saver = PlayerSheetState.saver(scope)
        val saverScope = SaverScope { true }

        val open = sheet()
        open.expand() // mid-flight counts as where it's going
        val saved = with(saver) { saverScope.save(open) }!!
        val restored = saver.restore(saved)!!
        assertEquals(PlayerSheetAnchor.FULL, restored.settledAnchor)
        assertEquals(1f, restored.progress, 0f)
        assertTrue(restored.isWarm)

        val closed = sheet()
        val restoredClosed = saver.restore(with(saver) { saverScope.save(closed) }!!)!!
        assertEquals(PlayerSheetAnchor.MINI, restoredClosed.settledAnchor)
        assertFalse(restoredClosed.isWarm)

        // A QUEUE saved by a later build opens at FULL in phase A.
        assertEquals(PlayerSheetAnchor.FULL, saver.restore("QUEUE")!!.settledAnchor)
        assertEquals(PlayerSheetAnchor.MINI, saver.restore("nonsense")!!.settledAnchor)
    }

    @Test
    fun `Now Playing stays warm for the grace period after closing, then cools`() = runTest {
        val s = sheet(keepWarmMs = 30_000L)
        s.expand()
        settle()
        s.collapse()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)
        assertTrue(s.isWarm)
        advanceTimeBy(28_000)
        runCurrent()
        assertTrue(s.isWarm)
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(s.isWarm)
    }

    @Test
    fun `reopening within the grace period keeps it warm`() = runTest {
        val s = sheet(keepWarmMs = 30_000L)
        s.expand(); settle()
        s.collapse(); advanceTimeBy(10_000); runCurrent()
        s.expand(); advanceTimeBy(25_000); runCurrent()
        assertTrue(s.isWarm)
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
    }

    @Test
    fun `a touch warms for at least the touch grace even with no keep-warm`() = runTest {
        val s = sheet(keepWarmMs = 0L)
        s.warm()
        assertTrue(s.isWarm)
        advanceTimeBy(MIN_TOUCH_WARM_MS - 1)
        runCurrent()
        assertTrue(s.isWarm)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(s.isWarm)
    }

    @Test
    fun `Back while the player is closing is consumed without restarting the snap`() = runTest {
        val s = sheet()
        s.snapTo(PlayerSheetAnchor.FULL)
        s.collapse()
        advanceTimeBy(120)
        runCurrent()
        val mid = s.progress
        assertTrue(mid > 0f && mid < 1f)
        assertTrue(s.handleBack())
        advanceTimeBy(16)
        runCurrent()
        assertTrue("still closing, not restarted", s.progress <= mid)
        settle()
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)
        assertFalse(s.handleBack())
    }

    @Test
    fun `a long drag up released with a tiny reverse twitch still opens`() = runTest {
        val s = sheet()
        s.beginDrag()
        s.dragBy(300f * density)
        s.endDrag(velocityUpDpS = -650f, recentUpDp = 0.4f)
        settle()
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
    }

    @Test
    fun `in RTL the mini cover sits at the card's right end`() = runTest {
        val s = sheet()
        s.isRtl = true
        val card = s.effectiveMiniCardRect()
        val art = s.miniArtRect()
        assertEquals(card.right - 8f * density, art.right, 1e-3f)
        assertEquals(44f * density, art.width, 1e-3f)
    }

    @Test
    fun `without a mini player the sheet grows from where it would be`() = runTest {
        val s = sheet()
        s.miniCardRect = null
        s.navBarBottomPx = 66f
        val fallback = s.effectiveMiniCardRect()
        // 8 dp margins, 60 dp tall, above an 80 dp tab bar and the system bar.
        assertEquals(8f * density, fallback.left, 1e-3f)
        assertEquals(60f * density, fallback.height, 1e-3f)
        assertEquals(2340f - 66f - 88f * density, fallback.bottom, 1e-3f)
        assertNull(s.miniCardRect)
        // The mini cover: 44 dp, 8 dp in.
        val art = s.miniArtRect()
        assertEquals(fallback.left + 8f * density, art.left, 1e-3f)
        assertEquals(44f * density, art.width, 1e-3f)
    }
}
