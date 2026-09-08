package com.stash.core.data.cache

import app.cash.turbine.test
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Pixel 6, 2026-09-08: an uncached artist page took 13.8 s to show anything.
 * The YouTube profile was in hand at 3.4 s; the page then waited for the
 * enrichments, and MusicBrainz took 6.5 s (a blocking call the 4 s coroutine
 * timeout cannot interrupt). The first paint must be the YouTube profile the
 * moment it arrives; the enrichments follow as a second emission.
 */
class ArtistCacheFirstPaintTest {

    private fun mkProfile(id: String, name: String) = ArtistProfile(
        id = id, name = name, avatarUrl = null, subscribersText = null,
        popular = emptyList(), albums = emptyList(), singles = emptyList(), related = emptyList(),
    )

    /** An enricher that answers only when the test lets it. */
    private class GatedEnricher : ArtistAboutEnricher {
        val gate = CompletableDeferred<ArtistAbout?>()
        override suspend fun enrich(artistName: String): ArtistAbout? = gate.await()
    }

    @Test
    fun `a miss paints the YouTube profile before the enrichment answers`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "Nick Cave"))
        val enricher = GatedEnricher()
        val cache = ArtistCache(dao, api, now = { 1_000L }, aboutEnricher = enricher)

        cache.get("UC1").test {
            val first = awaitItem()
            assertTrue("first emission must not wait for the enrichment", first is CachedProfile.Partial)
            assertEquals("Nick Cave", first.profile.name)
            assertFalse(enricher.gate.isCompleted)

            enricher.gate.complete(ArtistAbout(bio = "Australian musician", socials = emptyList(), photoUrl = null))
            val second = awaitItem()
            assertTrue(second is CachedProfile.Fresh)
            assertEquals("Australian musician", second.profile.about?.bio)
            awaitComplete()
        }
    }

    @Test
    fun `only the enriched profile is persisted`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "Nick Cave"))
        val enricher = GatedEnricher()
        val cache = ArtistCache(dao, api, now = { 1_000L }, aboutEnricher = enricher)

        cache.get("UC1").test {
            awaitItem()
            assertEquals(null, dao.get("UC1"))
            enricher.gate.complete(null)
            awaitItem()
            assertTrue(dao.get("UC1") != null)
            awaitComplete()
        }
    }
}
