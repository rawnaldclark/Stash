package com.stash.feature.nowplaying.sheet

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSheetGesturesTest {

    @Test
    fun `the lift's direction is the movement over the last 20 ms`() {
        // Moving up (y falling) 10 px per 8 ms, then lifted.
        val up = listOf(0L to 500f, 8L to 490f, 16L to 480f, 24L to 470f, 32L to 460f)
        assertEquals(-30f, recentMovementPx(up), 0f) // 32 -> 8 ms: the oldest sample 20+ ms back
        // Up a long way, then held still for the last 24 ms: no direction.
        val stopped = listOf(0L to 500f, 8L to 400f, 16L to 300f, 40L to 300f)
        assertEquals(0f, recentMovementPx(stopped), 0f)
        // A hair back at the very end doesn't turn a fast upward lift around.
        val twitch = listOf(0L to 300f, 10L to 200f, 20L to 100f, 28L to 101f)
        assertEquals(-199f, recentMovementPx(twitch), 0f)
    }

    @Test
    fun `one sample (or none) has no direction`() {
        assertEquals(0f, recentMovementPx(emptyList()), 0f)
        assertEquals(0f, recentMovementPx(listOf(5L to 100f)), 0f)
    }
}
