package com.stash.feature.nowplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothPositionTest {
    @Test fun `between ticks the lyrics clock runs at the song's speed`() {
        assertEquals(10_250L, extrapolatedPositionMs(10_000L, elapsedMs = 250L, speed = 1f))
        assertEquals(10_500L, extrapolatedPositionMs(10_000L, elapsedMs = 250L, speed = 2f))
        assertEquals(10_125L, extrapolatedPositionMs(10_000L, elapsedMs = 250L, speed = 0.5f))
    }
}
