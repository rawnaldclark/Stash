package com.stash.feature.nowplaying.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.ImageRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A new song's front answers the skip at once; pages taking turns within a song, or turned by
 * hand, keep the slow crossfade.
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
}
