package com.stash.core.data.cache

import app.cash.turbine.test
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for the [ArtistCache] SWR contract.
 *
 * Covers the five behaviours the UI layer depends on:
 *  1. Cold miss -> Fresh after network populates the cache.
 *  2. Warm hit within TTL -> Fresh, without ever calling the API.
 *  3. Warm hit past TTL -> Stale now, then Fresh after the refresh completes.
 *  4. Warm hit past TTL + network failure -> Stale sticks with
 *     refreshFailed=true (spec Phase 3 advisory lock — stale data must
 *     stay visible; we never swap the screen into an error state).
 *  5. The memory LRU caps at 20 entries.
 *
 * `YTMusicApiClient` is mocked with mockito-kotlin; the DAO tier is the
 * pure-JVM [InMemoryDao] fake. Time is injected via the `now` lambda so
 * tests don't sleep.
 */
class ArtistCacheTest {

    private fun mkProfile(id: String, name: String = "Name") = ArtistProfile(
        id = id,
        name = name,
        avatarUrl = null,
        subscribersText = null,
        popular = emptyList(),
        albums = emptyList(),
        singles = emptyList(),
        related = emptyList(),
    )

    @Test
    fun `miss emits Fresh after network fetch populates cache`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "A"))
        val cache = ArtistCache(dao, api, now = { 1_000L })

        cache.get("UC1").test {
            val first = awaitItem()
            assertTrue(first is CachedProfile.Fresh)
            assertEquals("A", (first as CachedProfile.Fresh).profile.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hit fresh returns Fresh without calling api`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "A", fetchedAt = 1_000L))
        }
        val api = mock<YTMusicApiClient>()
        // 1 min later — well inside the 6h TTL.
        val cache = ArtistCache(dao, api, now = { 1_000L + 60_000L })

        val result = cache.get("UC1").first()
        assertTrue(result is CachedProfile.Fresh)
        assertEquals("A", (result as CachedProfile.Fresh).profile.name)
        Mockito.verifyNoInteractions(api)
    }

    @Test
    fun `hit stale emits Stale then Fresh after refresh`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "Old", fetchedAt = 0L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "New"))
        val ttl7h = 7 * 60 * 60 * 1000L
        val cache = ArtistCache(dao, api, now = { ttl7h })

        cache.get("UC1").test {
            val stale = awaitItem()
            assertTrue(stale is CachedProfile.Stale)
            assertEquals("Old", (stale as CachedProfile.Stale).profile.name)
            assertFalse(stale.refreshFailed)

            val fresh = awaitItem()
            assertTrue(fresh is CachedProfile.Fresh)
            assertEquals("New", (fresh as CachedProfile.Fresh).profile.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Spec advisory — Phase 3: stale-refresh-failure stays Stale + flips refreshFailed. */
    @Test
    fun `stale refresh failure keeps Stale with refreshFailed=true`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "Cached", fetchedAt = 0L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenThrow(RuntimeException("offline"))
        val ttl7h = 7 * 60 * 60 * 1000L
        val cache = ArtistCache(dao, api, now = { ttl7h })

        cache.get("UC1").test {
            val stale = awaitItem() as CachedProfile.Stale
            assertEquals("Cached", stale.profile.name)
            assertFalse(stale.refreshFailed)
            // Second emission must be another Stale — not Error — with refreshFailed=true.
            val after = awaitItem()
            assertTrue(after is CachedProfile.Stale)
            assertTrue((after as CachedProfile.Stale).refreshFailed)
            assertEquals("Cached", after.profile.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `memory evicts oldest beyond 20`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        repeat(25) { i ->
            whenever(api.getArtist(eq("UC$i"))).thenReturn(mkProfile("UC$i"))
        }
        val cache = ArtistCache(dao, api, now = { 1L })
        repeat(25) { i -> cache.get("UC$i").first() }

        assertTrue(cache.memoryContains("UC24"))
        assertFalse(cache.memoryContains("UC0"))
    }
}
