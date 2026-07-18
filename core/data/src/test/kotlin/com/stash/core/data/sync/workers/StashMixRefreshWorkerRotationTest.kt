package com.stash.core.data.sync.workers

import com.stash.core.data.sync.workers.StashMixRefreshWorker.Companion.blendRecentFirst
import com.stash.core.data.sync.workers.StashMixRefreshWorker.Companion.rotateSurvivorWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #287: the survivor-window rotation and recent-first seed blend that make
 * Daily Discover's Refresh actually refresh.
 */
class StashMixRefreshWorkerRotationTest {

    private val pool = (1L..100L).toList() // newest-first by contract

    @Test
    fun `pool at or under cap keeps full membership but rotates order`() {
        val window = rotateSurvivorWindow(pool.take(10), cap = 20, seed = 7L)
        assertEquals(pool.take(10).toSet(), window.toSet())
        // Order itself must rotate across seeds — a static order is the
        // invisible-refresh bug at small-pool scale.
        assertNotEquals(
            rotateSurvivorWindow(pool.take(10), cap = 20, seed = 1L),
            rotateSurvivorWindow(pool.take(10), cap = 20, seed = 2L),
        )
    }

    @Test
    fun `zero cap yields empty`() {
        assertEquals(emptyList<Long>(), rotateSurvivorWindow(pool, cap = 0, seed = 7L))
    }

    @Test
    fun `newest head is always IN the window — membership, not position`() {
        val window = rotateSurvivorWindow(pool, cap = 20, seed = 42L)
        // 30% of 20 = 6 newest ids are guaranteed present; their position
        // rotates (pinning them froze the visible top + cover mosaic).
        assertTrue(window.containsAll(pool.take(6)))
    }

    @Test
    fun `position zero rotates across seeds`() {
        val firsts = (1L..8L).map { rotateSurvivorWindow(pool, cap = 20, seed = it).first() }.toSet()
        // Eight seeds should not all land the same opener (art mosaic driver).
        assertTrue(firsts.size > 1)
    }

    @Test
    fun `window is exactly cap sized with no duplicates`() {
        val window = rotateSurvivorWindow(pool, cap = 20, seed = 42L)
        assertEquals(20, window.size)
        assertEquals(20, window.toSet().size)
        assertTrue(window.all { it in pool })
    }

    @Test
    fun `same seed is deterministic`() {
        assertEquals(
            rotateSurvivorWindow(pool, cap = 20, seed = 42L),
            rotateSurvivorWindow(pool, cap = 20, seed = 42L),
        )
    }

    @Test
    fun `different seeds rotate the tail`() {
        val a = rotateSurvivorWindow(pool, cap = 20, seed = 1L)
        val b = rotateSurvivorWindow(pool, cap = 20, seed = 2L)
        // Heads match by design; the shuffled remainder must differ.
        assertNotEquals(a, b)
    }

    @Test
    fun `blend puts recent first and dedups the fallback`() {
        val blended = blendRecentFirst(
            recent = listOf("A", "B"),
            fallback = listOf("B", "C", "D"),
            limit = 3,
        )
        assertEquals(listOf("A", "B", "C"), blended)
    }

    @Test
    fun `blend with empty recent is just the capped fallback`() {
        assertEquals(
            listOf("X", "Y"),
            blendRecentFirst(recent = emptyList(), fallback = listOf("X", "Y"), limit = 8),
        )
    }
}
