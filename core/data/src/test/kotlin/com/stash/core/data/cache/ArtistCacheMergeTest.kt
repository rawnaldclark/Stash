package com.stash.core.data.cache

import app.cash.turbine.test
import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.MergedDiscography
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for the [ArtistCache] × [DiscographySupplement] wiring (Task 9).
 *
 * Proves the Qobuz supplement is merged at BOTH fetch sites (cold miss AND
 * stale refresh) and that any supplement failure/timeout degrades to YT-only
 * WITHOUT escaping to the collector.
 *
 * Mirrors [ArtistCacheTest]: Turbine `.test`, mockito-kotlin `YTMusicApiClient`,
 * the pure-JVM [InMemoryDao] fake, and a fixed `now` clock. The supplement is a
 * hand-rolled fake driven by a lambda so each test picks its own behaviour.
 */
// A cold miss paints the YouTube profile first (CachedProfile.Partial); the merged
// profile is the Fresh emission that follows, so these read the Fresh item.
class ArtistCacheMergeTest {

    private val loveless = AlbumSummary(
        id = "qobuz-loveless",
        title = "Loveless",
        artist = "MBV",
        thumbnailUrl = null,
        year = "1991",
        source = AlbumSource.QOBUZ,
    )

    /** Fake supplement: whatever the lambda returns (or throws) is the merge result. */
    private class FakeSupplement(
        val block: suspend (List<AlbumSummary>, List<AlbumSummary>) -> MergedDiscography,
    ) : DiscographySupplement {
        override suspend fun mergeInto(
            artistName: String,
            ytAlbums: List<AlbumSummary>,
            ytSingles: List<AlbumSummary>,
        ): MergedDiscography = block(ytAlbums, ytSingles)
    }

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

    /** Appends the Qobuz "Loveless" album to whatever YT returned. */
    private fun mergingSupplement() = FakeSupplement { ytAlbums, ytSingles ->
        MergedDiscography(ytAlbums + loveless, ytSingles)
    }

    private fun AlbumSummary.isLoveless() =
        title == "Loveless" && source == AlbumSource.QOBUZ

    @Test
    fun `cold miss merges the Qobuz supplement into the Fresh profile`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "MBV"))
        val cache = ArtistCache(dao, api, now = { 1_000L }, supplement = mergingSupplement())

        cache.get("UC1").test {
            assertTrue(awaitItem() is CachedProfile.Partial)   // the YouTube profile paints first
            val fresh = awaitItem()
            assertTrue(fresh is CachedProfile.Fresh)
            assertTrue(
                "cold-miss Fresh must contain the Qobuz album",
                (fresh as CachedProfile.Fresh).profile.albums.any { it.isLoveless() },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stale refresh also merges the Qobuz supplement`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "Old", fetchedAt = 0L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "New"))
        val ttl7h = 7 * 60 * 60 * 1000L
        val cache = ArtistCache(dao, api, now = { ttl7h }, supplement = mergingSupplement())

        cache.get("UC1").test {
            val stale = awaitItem()
            assertTrue(stale is CachedProfile.Stale)

            val fresh = awaitItem()
            assertTrue(fresh is CachedProfile.Fresh)
            assertTrue(
                "stale-refresh Fresh must ALSO contain the Qobuz album",
                (fresh as CachedProfile.Fresh).profile.albums.any { it.isLoveless() },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `supplement failure falls back to YT-only with no exception`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "MBV"))
        val throwing = FakeSupplement { _, _ -> throw RuntimeException("qobuz down") }
        val cache = ArtistCache(dao, api, now = { 1_000L }, supplement = throwing)

        val result = cache.get("UC1").first { it is CachedProfile.Fresh }
        assertTrue(result is CachedProfile.Fresh)
        assertTrue(
            "supplement failure must degrade to YT-only (no Qobuz album)",
            (result as CachedProfile.Fresh).profile.albums.none { it.isLoveless() },
        )
    }

    @Test
    fun `supplement timeout falls back to YT-only with no exception`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "MBV"))
        // Delay well past the 4s SUPPLEMENT_TIMEOUT_MS — withTimeout must trip.
        val slow = FakeSupplement { ytAlbums, ytSingles ->
            delay(30_000L)
            MergedDiscography(ytAlbums + loveless, ytSingles)
        }
        val cache = ArtistCache(dao, api, now = { 1_000L }, supplement = slow)

        val result = cache.get("UC1").first { it is CachedProfile.Fresh }
        assertTrue(result is CachedProfile.Fresh)
        assertTrue(
            "supplement timeout must degrade to YT-only (no Qobuz album)",
            (result as CachedProfile.Fresh).profile.albums.none { it.isLoveless() },
        )
    }
}
