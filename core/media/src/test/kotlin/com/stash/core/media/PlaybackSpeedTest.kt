package com.stash.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun `song time becomes wall time at the playing speed`() {
        assertEquals(3_000L, mediaToWallMs(6_000L, 2f))
        assertEquals(12_000L, mediaToWallMs(6_000L, 0.5f))
        assertEquals(6_000L, mediaToWallMs(6_000L, 1f))
    }

    @Test fun `wall time becomes song time at the playing speed`() {
        assertEquals(12_000L, wallToMediaMs(6_000L, 2f))
        assertEquals(1_500L, wallToMediaMs(6_000L, 0.25f))
    }

    @Test fun `a speed that isn't a positive number counts as normal`() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("speed $bad", 6_000L, mediaToWallMs(6_000L, bad))
            assertEquals("speed $bad", 6_000L, wallToMediaMs(6_000L, bad))
        }
    }
}
