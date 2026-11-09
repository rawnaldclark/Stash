package com.stash.feature.nowplaying.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.ImageRequest
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A new song's front answers the skip at once; pages taking turns within a song, or turned by
 * hand, keep the slow crossfade. Turned on, the art comes out, on the front when its first page
 * fails or is slow.
 */
@RunWith(RobolectricTestRunner::class)
class FullArtStateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun page(key: String) = FullArtPage(key, ImageRequest.Builder(context).data(key).build(), key)

    private fun FullArtState.show(pages: List<FullArtPage>) {
        this.pages = pages
        schedule = fullArtSchedule(200_000L, pages.size)
        loaded = pages.associate { it.key to Unit }
    }

    /** Takes the wake [FullArtState.open] leaves, so a test sees only the wake it's about. */
    private suspend fun FullArtState.takeOpenWake() = awaitWake(STILL_TICK_MS)

    @Test
    fun `a new song's front fades in quickly`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 0L, playing = true, failed = emptySet(), still = false)

        state.show(listOf(page("front:b")))
        state.newSong(2_000L)
        state.tick(2_016L, 0L, playing = true, failed = emptySet(), still = false)

        assertEquals("front:b", state.shown.last().page.key)
        assertEquals(300L, state.shown.last().fadeMs)
        // Gone from under it once the short fade is over, not 2.2 s later.
        state.tick(2_400L, 400L, playing = true, failed = emptySet(), still = false)
        assertEquals(listOf("front:b"), state.shown.map { it.page.key })
    }

    @Test
    fun `a new song with no pages yet doesn't crash, and its front fades in once they come`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 0L, playing = true, failed = emptySet(), still = false)

        // Ending a Listen Together session: the song changes while its pages are still empty.
        // This threw ArithmeticException (divide by zero) in pageFor.
        state.show(emptyList())
        state.newSong(2_000L)
        state.tick(2_016L, 0L, playing = true, failed = emptySet(), still = false)
        assertEquals("front:a", state.shown.last().page.key)

        state.show(listOf(page("front:b"), page("back:b")))
        state.tick(2_032L, 16L, playing = true, failed = emptySet(), still = false)
        assertEquals("front:b", state.shown.last().page.key)
        assertEquals(300L, state.shown.last().fadeMs)
    }

    @Test
    fun `a page turned by hand keeps the slow crossfade`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 0L, playing = true, failed = emptySet(), still = false)

        state.turn(1)
        state.tick(1_032L, 16L, playing = true, failed = emptySet(), still = false)

        assertEquals("back:a", state.shown.last().page.key)
        assertEquals(2_200L, state.shown.last().fadeMs)
    }

    @Test
    fun `with motion still, ticks every STILL_TICK_MS keep the clocks with real time`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        // Ten seconds of playback at the still rate (500 ms apart, not a frame apart).
        var now = 1_000L
        repeat(20) {
            now += STILL_TICK_MS
            state.tick(now, now - 1_000L, playing = true, failed = emptySet(), still = true)
        }
        // A frame's 100 ms clamp would have counted 2 s of these 10.
        assertEquals(10_000L, state.playMs)
    }

    @Test
    fun `with motion still, a tick doesn't move the frame clock, so the art isn't redrawn`() {
        val state = FullArtState()
        state.show(listOf(page("front:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 16L, playing = true, failed = emptySet(), still = false)
        val drawnAt = state.frameMs
        state.tick(1_516L, 516L, playing = true, failed = emptySet(), still = true)
        assertEquals(drawnAt, state.frameMs)
    }

    @Test
    fun `playing, every tick moves the frame clock, so the art pans`() {
        val state = FullArtState()
        state.show(listOf(page("front:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        assertTrue(state.tick(1_016L, 16L, playing = true, failed = emptySet(), still = false))
        assertEquals(1_016L, state.frameMs)
        assertTrue(state.tick(1_032L, 32L, playing = true, failed = emptySet(), still = false))
        assertEquals(1_032L, state.frameMs)
    }

    @Test
    fun `paused with nothing fading, a tick doesn't move the frame clock and asks for no next frame`() {
        // The Pixel 6 Pro test: full art out, paused, 619 frames drawn in 5 s for a still picture.
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 16L, playing = true, failed = emptySet(), still = false)
        val drawnAt = state.frameMs
        assertFalse(state.tick(1_032L, 16L, playing = false, failed = emptySet(), still = false))
        assertFalse(state.tick(1_532L, 16L, playing = false, failed = emptySet(), still = false))
        assertEquals(drawnAt, state.frameMs)
    }

    @Test
    fun `paused, a page turned by hand still fades in frame by frame, then the loop rests`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        assertFalse(state.tick(1_016L, 0L, playing = false, failed = emptySet(), still = false))

        state.turn(1)
        assertTrue(state.tick(1_032L, 0L, playing = false, failed = emptySet(), still = false))
        assertEquals(1_032L, state.frameMs)
        assertTrue(state.tick(2_000L, 0L, playing = false, failed = emptySet(), still = false))
        assertEquals(2_000L, state.frameMs)

        // The fade over (2.2 s), the page under it is let go and nothing moves any more.
        assertFalse(state.tick(3_300L, 0L, playing = false, failed = emptySet(), still = false))
        assertEquals(listOf("back:a"), state.shown.map { it.page.key })
    }

    @Test
    fun `opening wakes a still loop at once`() = runTest {
        // Reopened while paused with its pages in hand: the art grows out now, not a still tick later.
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        val before = currentTime
        state.awaitWake(STILL_TICK_MS)
        assertEquals(0L, currentTime - before)
    }

    @Test
    fun `playing with Full art pan off or lyrics up, a tick doesn't move the frame clock and asks for no next frame`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 16L, playing = true, failed = emptySet(), still = false)
        val drawnAt = state.frameMs
        assertFalse(state.tick(1_032L, 32L, playing = true, failed = emptySet(), still = false, pans = false))
        assertFalse(state.tick(1_532L, 532L, playing = true, failed = emptySet(), still = false, pans = false))
        assertEquals(drawnAt, state.frameMs)
    }

    @Test
    fun `playing with nothing to pan, a page turned by hand still fades in frame by frame`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        assertFalse(state.tick(1_016L, 16L, playing = true, failed = emptySet(), still = false, pans = false))

        state.turn(1)
        assertTrue(state.tick(1_032L, 32L, playing = true, failed = emptySet(), still = false, pans = false))
        assertEquals(1_032L, state.frameMs)
    }

    @Test
    fun `playing with nothing to pan, the rested ticks keep the clocks with real time`() {
        // One page: no page change (and its fade) in the middle of the ten seconds.
        val state = FullArtState()
        state.show(listOf(page("front:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 16L, playing = true, failed = emptySet(), still = false, pans = false)
        val before = state.playMs
        // Ten seconds of playback at the still rate (500 ms apart, not a frame apart).
        var now = 1_016L
        repeat(20) {
            now += STILL_TICK_MS
            state.tick(now, now - 1_000L, playing = true, failed = emptySet(), still = false, pans = false)
        }
        // A frame's 100 ms clamp would have counted 2 s of these 10.
        assertEquals(10_000L, state.playMs - before)
    }

    @Test
    fun `play resuming after a rest moves the pan no more than a frame's step`() {
        // Most of the step since the last (paused) tick was paused: it isn't played time.
        val state = FullArtState()
        state.show(listOf(page("front:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        assertFalse(state.tick(1_016L, 0L, playing = false, failed = emptySet(), still = false))
        val before = state.playMs
        assertTrue(state.tick(1_516L, 0L, playing = true, failed = emptySet(), still = false))
        assertEquals(100L, state.playMs - before)
    }

    @Test
    fun `with motion still, a tick asks for no next frame even while playing`() {
        val state = FullArtState()
        state.show(listOf(page("front:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        assertFalse(state.tick(1_500L, 500L, playing = true, failed = emptySet(), still = true))
    }

    @Test
    fun `with motion still, a page turned by hand is fully shown at once`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_500L, 500L, playing = true, failed = emptySet(), still = true)

        state.turn(1)
        state.tick(2_000L, 1_000L, playing = true, failed = emptySet(), still = true)

        // Born a whole fade ago: no crossfade frames to run.
        val shown = state.shown.last()
        assertEquals("back:a", shown.page.key)
        assertEquals(2_000L - shown.fadeMs, shown.bornMs)
    }

    @Test
    fun `a page turned by hand wakes a still loop at once`() = runTest {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.takeOpenWake()
        state.turn(1)
        val before = currentTime
        state.awaitWake(STILL_TICK_MS)
        assertEquals("woken, not after a full still tick", 0L, currentTime - before)
    }

    @Test
    fun `a new song's front wakes a still loop at once`() = runTest {
        val state = FullArtState()
        state.show(listOf(page("front:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.takeOpenWake()
        state.newSong(2_000L)
        val before = currentTime
        state.awaitWake(STILL_TICK_MS)
        assertEquals(0L, currentTime - before)
    }

    @Test
    fun `with nothing pending a still loop waits its tick`() = runTest {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.takeOpenWake()
        val before = currentTime
        state.awaitWake(STILL_TICK_MS)
        assertEquals(STILL_TICK_MS, currentTime - before)
        // One wake is one early tick: the next pause is a full tick again.
        state.wake()
        state.awaitWake(STILL_TICK_MS)
        val afterWake = currentTime
        state.awaitWake(STILL_TICK_MS)
        assertEquals(STILL_TICK_MS, currentTime - afterWake)
    }

    // Full art turned on and staying square (2026-11-08: "have to press the arrows several times"):
    // the art grows only once the page on screen is in hand, and that used to be able to never happen.

    private fun FullArtState.inHand(vararg keys: String) {
        loaded = keys.associateWith { Unit }
    }

    private fun FullArtState.growTo(target: Boolean = true) = fullArtGrowTo(target, visible = true, ready = ready)

    @Test
    fun `a first page that fails gives way to the front at once, and the art comes out`() {
        val state = FullArtState()
        val told = mutableListOf<Int>()
        state.onPage = { told += it }
        state.show(listOf(page("front:a"), page("back:a")))
        state.inHand("front:a")
        // Opened on the back (the square art was showing it), which can't load.
        state.open(now = 1_000L, position = 0L, start = 1)
        state.tick(1_016L, 0L, playing = true, failed = setOf("back:a"), still = false)

        assertTrue(state.ready)
        assertEquals(listOf("front:a"), state.shown.map { it.page.key })
        // The square art follows, so turning full art off lands on the page it shows.
        assertEquals(0, told.last())
        assertEquals(1f, state.growTo())
    }

    @Test
    fun `a first page not in hand after the grace falls back to the front`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a"), page("inside:a")))
        state.inHand("front:a")
        state.open(now = 1_000L, position = 0L, start = 2)

        // Still coming within the grace: it waits for it, the art held in the square.
        state.tick(1_000L + FIRST_PAGE_GRACE_MS - 1, 0L, playing = true, failed = emptySet(), still = false)
        assertFalse(state.ready)
        assertEquals("inside:a", state.shown.last().page.key)
        assertEquals(null, state.growTo())

        state.tick(1_000L + FIRST_PAGE_GRACE_MS, 0L, playing = true, failed = emptySet(), still = false)
        assertTrue(state.ready)
        assertEquals(listOf("front:a"), state.shown.map { it.page.key })
        // Shown whole at once, not faded in from nothing as the art grows.
        assertEquals(1_000L + FIRST_PAGE_GRACE_MS - state.shown.last().fadeMs, state.shown.last().bornMs)
        assertEquals(1f, state.growTo())
    }

    @Test
    fun `with motion still, the first still tick past the grace falls back`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.inHand("front:a")
        state.open(now = 1_000L, position = 0L, start = 1)
        state.tick(1_000L + STILL_TICK_MS, 0L, playing = false, failed = emptySet(), still = true)
        assertTrue(state.ready)
        assertEquals("front:a", state.shown.last().page.key)
    }

    @Test
    fun `a slow front waits for itself, and a failed one gives way to the next page`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.inHand()
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(2_000L, 0L, playing = true, failed = emptySet(), still = false)
        assertFalse(state.ready)
        assertEquals("front:a", state.shown.last().page.key)

        state.inHand("back:a")
        state.tick(2_016L, 0L, playing = true, failed = setOf("front:a"), still = false)
        assertTrue(state.ready)
        assertEquals("back:a", state.shown.last().page.key)
    }

    @Test
    fun `with every page failed, the square art stays`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.inHand()
        state.open(now = 1_000L, position = 0L, start = 1)
        state.tick(2_000L, 0L, playing = true, failed = setOf("front:a", "back:a"), still = false)
        assertFalse(state.ready)
        assertEquals(null, state.growTo())
    }

    @Test
    fun `closing clears ready and the pages shown, so a reopen waits for its own page`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 0L, playing = true, failed = emptySet(), still = false)
        assertTrue(state.ready)

        // Shrunk back into the square: the layer leaves, and its pages in hand with it.
        state.close()
        assertFalse(state.ready)
        assertTrue(state.shown.isEmpty())

        // Reopened: nothing in hand yet. It used to start ready from the last open and grow out over
        // an empty page (the square hidden), then freeze part-way when the open cleared it.
        state.inHand()
        assertEquals("held in the square", null, state.growTo())
        state.open(now = 5_000L, position = 0L, start = 0)
        state.tick(5_016L, 0L, playing = true, failed = emptySet(), still = false)
        assertFalse(state.ready)
        assertEquals(null, state.growTo())

        state.inHand("front:a")
        state.tick(5_032L, 0L, playing = true, failed = emptySet(), still = false)
        assertTrue(state.ready)
        assertEquals(1f, state.growTo())
    }

    @Test
    fun `a reopen tells the square art its page again`() {
        val state = FullArtState()
        val told = mutableListOf<Int>()
        state.onPage = { told += it }
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 0L, playing = true, failed = emptySet(), still = false)
        state.close()

        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 5_000L, position = 0L, start = 0)
        state.tick(5_016L, 0L, playing = true, failed = emptySet(), still = false)
        assertEquals(listOf(0, 0), told)
    }

    @Test
    fun `turned off and on again while it grows, it heads straight back out`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 0L, playing = true, failed = emptySet(), still = false)
        assertEquals(1f, state.growTo())

        // A second tap mid-grow: back into the square, whatever the flag says.
        assertEquals(0f, state.growTo(target = false))
        assertEquals(0f, fullArtGrowTo(target = false, visible = true, ready = false))

        // A third tap before it had shrunk away (the layer never left, so it never closed): the same
        // page is in hand, so it stays ready and grows out again, never held part-way.
        state.open(now = 1_300L, position = 300L, start = 0)
        assertTrue(state.ready)
        assertEquals(1f, state.growTo())
        state.tick(1_316L, 316L, playing = true, failed = emptySet(), still = false)
        assertTrue(state.ready)
        assertEquals("front:a", state.shown.last().page.key)
    }

    @Test
    fun `turned on again mid-shrink onto a page not in hand, it waits in the square, then comes out`() {
        val state = FullArtState()
        state.show(listOf(page("front:a"), page("back:a")))
        state.inHand("front:a")
        state.open(now = 1_000L, position = 0L, start = 0)
        state.tick(1_016L, 0L, playing = true, failed = emptySet(), still = false)
        assertTrue(state.ready)

        // Reopened on the back, not in hand: not ready, so the square isn't hidden over a page that
        // can't be drawn (it hides only while the art is out and ready).
        state.open(now = 1_300L, position = 300L, start = 1)
        assertFalse(state.ready)
        assertEquals(null, state.growTo())

        // Held only until the grace: then the front, which is in hand.
        state.tick(1_300L + FIRST_PAGE_GRACE_MS, 700L, playing = true, failed = emptySet(), still = false)
        assertTrue(state.ready)
        assertEquals("front:a", state.shown.last().page.key)
        assertEquals(1f, state.growTo())
    }

    @Test
    fun `leaving the top of the sheet snaps the art back into the square, ready or not`() {
        assertEquals(0f, fullArtGrowTo(target = false, visible = false, ready = true))
        assertEquals(0f, fullArtGrowTo(target = false, visible = false, ready = false))
    }
}
