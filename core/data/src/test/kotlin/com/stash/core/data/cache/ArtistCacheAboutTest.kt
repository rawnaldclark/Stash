package com.stash.core.data.cache

import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.SocialLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for the [ArtistCache] × [ArtistAboutEnricher] wiring.
 *
 * Proves the About enricher runs as a SECOND best-effort supplement,
 * concurrently with the Qobuz discography merge, and that any About
 * failure/timeout degrades to `about == null` WITHOUT escaping to the
 * collector or disturbing the discography lists.
 *
 * Mirrors [ArtistCacheTest]/[ArtistCacheMergeTest]: mockito-kotlin
 * `YTMusicApiClient`, the pure-JVM [InMemoryDao] fake, a fixed `now` clock,
 * and `NoopDiscographySupplement` for the discography path. The enricher is a
 * hand-rolled fake driven by a lambda so each test picks its own behaviour.
 */
// A cold miss paints the YouTube profile first (CachedProfile.Partial); the merged
// profile is the Fresh emission that follows, so these read the Fresh item.
class ArtistCacheAboutTest {

    /** Fake enricher: whatever the lambda returns (or throws) is the About result. */
    private class FakeEnricher(
        val block: suspend () -> ArtistAbout?,
    ) : ArtistAboutEnricher {
        override suspend fun enrich(artistName: String): ArtistAbout? = block()
    }

    private val album = AlbumSummary(
        id = "yt-album",
        title = "Album",
        artist = "MBV",
        thumbnailUrl = null,
        year = "1991",
        source = AlbumSource.YOUTUBE,
    )

    private fun mkProfile(id: String, name: String = "MBV") = ArtistProfile(
        id = id,
        name = name,
        avatarUrl = null,
        subscribersText = null,
        popular = emptyList(),
        albums = listOf(album),
        singles = emptyList(),
        related = emptyList(),
    )

    @Test
    fun `enricher throws falls back to null about with no exception`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1"))
        val throwing = FakeEnricher { throw RuntimeException("last.fm down") }
        val cache = ArtistCache(dao, api, now = { 1_000L }, aboutEnricher = throwing)

        val result = cache.get("UC1").first { it is CachedProfile.Fresh }
        assertTrue(result is CachedProfile.Fresh)
        assertNull((result as CachedProfile.Fresh).profile.about)
    }

    @Test
    fun `enricher timeout falls back to null about without disturbing discography`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1"))
        // Never returns — runTest's virtual clock advances the ABOUT_TIMEOUT_MS
        // withTimeout so the test doesn't actually hang.
        val hanging = FakeEnricher { delay(Long.MAX_VALUE); null }
        val cache = ArtistCache(dao, api, now = { 1_000L }, aboutEnricher = hanging)

        val result = cache.get("UC1").first { it is CachedProfile.Fresh }
        assertTrue(result is CachedProfile.Fresh)
        val profile = (result as CachedProfile.Fresh).profile
        assertNull(profile.about)
        // About failure must not disturb the discography the API/Qobuz path produced.
        assertEquals(listOf(album), profile.albums)
        assertEquals(emptyList<AlbumSummary>(), profile.singles)
    }

    @Test
    fun `enricher result is merged into the Fresh profile about`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1"))
        val about = ArtistAbout(
            bio = "Shoegaze pioneers.",
            socials = listOf(SocialLink(kind = "website", url = "https://mbv.example")),
            photoUrl = null,
        )
        val cache = ArtistCache(dao, api, now = { 1_000L }, aboutEnricher = FakeEnricher { about })

        val result = cache.get("UC1").first { it is CachedProfile.Fresh }
        assertTrue(result is CachedProfile.Fresh)
        assertEquals(about, (result as CachedProfile.Fresh).profile.about)
    }

    // Review on #770: a refresh whose Spotify part timed out swapped 1.7M for YouTube's 574K.

    private val withSpotify = ArtistAbout(bio = "b", monthlyListeners = 1_734_567L, topTrackTitles = listOf("Teen Age Riot"))

    /** A cold miss cached with [first], then a refresh past the TTL answering [second]: its Fresh profile. */
    private suspend fun refreshed(first: ArtistAbout?, second: ArtistAbout?): ArtistProfile {
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1"))
        var clock = 1_000L
        var answer = first
        val cache = ArtistCache(InMemoryDao(), api, now = { clock }, aboutEnricher = FakeEnricher { answer })
        cache.get("UC1").first { it is CachedProfile.Fresh }
        clock += 7 * 60 * 60 * 1000L // past the 6 h TTL
        answer = second
        return (cache.get("UC1").first { it is CachedProfile.Fresh } as CachedProfile.Fresh).profile
    }

    @Test
    fun `a refresh without Spotify's count keeps the cached one`() = runTest {
        val about = refreshed(withSpotify, ArtistAbout(bio = "new bio")).about!!
        assertEquals(1_734_567L, about.monthlyListeners)
        assertEquals(listOf("Teen Age Riot"), about.topTrackTitles)
        // The rest of About is the refresh's own.
        assertEquals("new bio", about.bio)
    }

    @Test
    fun `a refresh with no About at all still keeps the count, and opens no section`() = runTest {
        val about = refreshed(withSpotify, null).about!!
        assertEquals(1_734_567L, about.monthlyListeners)
        assertTrue(!about.hasSection)
    }

    @Test
    fun `a refresh with its own Spotify count replaces the cached one`() = runTest {
        val about = refreshed(withSpotify, ArtistAbout(bio = "b", monthlyListeners = 1_800_000L)).about!!
        assertEquals(1_800_000L, about.monthlyListeners)
    }
}
