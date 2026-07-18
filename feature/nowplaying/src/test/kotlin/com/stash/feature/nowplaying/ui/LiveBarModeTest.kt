package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBarModeTest {

    private val lines = listOf(LrcLine(timestampMs = 1_000L, text = "hi"))

    @Test fun `synced maps to Live when the live bar is enabled`() {
        val mode = liveBarModeFor(LyricsViewState.Synced(lines, plainFallback = "hi"), liveEnabled = true)
        assertEquals(LiveBarMode.Live(lines), mode)
    }

    @Test fun `synced maps to Static when the live bar is off (the default)`() {
        val mode = liveBarModeFor(LyricsViewState.Synced(lines, plainFallback = "hi"), liveEnabled = false)
        assertEquals(LiveBarMode.Static, mode)
    }

    @Test fun `plain maps to Static regardless of the toggle`() {
        assertEquals(LiveBarMode.Static, liveBarModeFor(LyricsViewState.Plain("text"), liveEnabled = true))
        assertEquals(LiveBarMode.Static, liveBarModeFor(LyricsViewState.Plain("text"), liveEnabled = false))
    }

    @Test fun `everything else maps to Hidden`() {
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Loading, liveEnabled = true))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.None, liveEnabled = true))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Instrumental, liveEnabled = true))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Error(retryable = true), liveEnabled = true))
    }
}
