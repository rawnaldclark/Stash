package com.stash.data.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.stash.data.ytmusic.model.ArtistLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * [YTMusicApiClient.getArtistPage] against a real artist page (`artist_new_releases.json`: Sabrina Carpenter's
 * `UCz51ZodJbYUNfkdPHOjJKKw`, fetched signed out with hl=en gl=US on 2026-11-01 and trimmed: menus and tracking
 * params dropped, Popular, Albums, Singles & EPs and Videos kept). Its Singles & EPs carousel has a "View all" link,
 * which [YTMusicApiClient.getArtist] follows and the one-browse page must not. `search_artists_sabrina.json` is the
 * artists-filtered search that finds her (same day, same settings, trimmed), for [YTMusicApiClient.lookUpArtist].
 */
class ArtistPageTest {

    private fun fixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().use { it.readText() }

    private fun innerAnswering(): InnerTubeClient {
        val inner = mock<InnerTubeClient>()
        val page = Json.parseToJsonElement(fixture("artist_new_releases.json")).jsonObject
        runBlocking {
            // Anything else (the "View all" grid) answers nothing, a mock's default: getArtist keeps the carousel then.
            whenever(inner.browse(eq("UCz51ZodJbYUNfkdPHOjJKKw"), anyOrNull())).thenReturn(page)
        }
        return inner
    }

    @Test
    fun `the page is one browse, never the View all grids`() = runTest {
        val inner = innerAnswering()
        YTMusicApiClient(inner).getArtistPage("UCz51ZodJbYUNfkdPHOjJKKw")
        verifyBlocking(inner, times(1)) { browse(any(), anyOrNull()) }
    }

    @Test
    fun `getArtist still follows the View all link`() = runTest {
        val inner = innerAnswering()
        YTMusicApiClient(inner).getArtist("UCz51ZodJbYUNfkdPHOjJKKw")
        verifyBlocking(inner, times(2)) { browse(any(), anyOrNull()) }
    }

    @Test
    fun `albums and singles come newest first, each with its year`() = runTest {
        val profile = YTMusicApiClient(innerAnswering()).getArtistPage("UCz51ZodJbYUNfkdPHOjJKKw")
        assertEquals("Sabrina Carpenter", profile.name)
        assertEquals("Man’s Best Friend", profile.albums.first().title)
        assertEquals("2025", profile.albums.first().year)
        assertEquals("MPREb_msfRVJDqlXJ", profile.albums.first().id)
        assertEquals(listOf("Bring Your Love", "Manchild"), profile.singles.take(2).map { it.title })
        assertEquals(listOf("2026", "2025"), profile.singles.take(2).map { it.year })
        // Every card is an album page and dated; each shelf runs newest first.
        (profile.albums + profile.singles).forEach {
            assertTrue(it.id, it.id.startsWith("MPREb_"))
            assertTrue(it.title, it.year?.matches(Regex("\\d{4}")) == true)
        }
        // Each shelf leads with its newest, but isn't strictly by year: this page's Albums end with a 2020
        // soundtrack after 2015. So "New from your artists" sorts by year itself (NewFromArtists.pick).
        for (shelf in listOf(profile.albums, profile.singles)) {
            val years = shelf.map { it.year!!.toInt() }
            assertEquals(years.max(), years.first())
        }
        assertEquals(listOf("2015", "2020"), profile.albums.takeLast(2).map { it.year })
    }

    private fun searching(): InnerTubeClient {
        val inner = mock<InnerTubeClient>()
        val found = Json.parseToJsonElement(fixture("search_artists_sabrina.json")).jsonObject
        runBlocking {
            whenever(inner.searchWithStatus(any(), anyOrNull())).thenReturn(InnerTubeClient.RequestOutcome(found, 200))
            whenever(inner.searchWithStatus(eq("Nobody"), anyOrNull()))
                .thenReturn(InnerTubeClient.RequestOutcome(null, InnerTubeClient.RequestOutcome.STATUS_NETWORK_ERROR))
        }
        return inner
    }

    @Test
    fun `looking an artist up tells an exact match, a loose one, no such artist and no answer apart`() = runTest {
        val api = YTMusicApiClient(searching())
        // The shelf answers with Sabrina Carpenter, for every name asked
        val exact = api.lookUpArtist("Sabrina Carpenter") as ArtistLookup.Found
        assertEquals("UCz51ZodJbYUNfkdPHOjJKKw", exact.artist.id)
        assertTrue(exact.exact)
        // A credit's main act is exact too
        assertTrue((api.lookUpArtist("Sabrina Carpenter, Someone") as ArtistLookup.Found).exact)
        // "Sabrina" is only contained in her name: found, but loose (the caller must confirm it)
        assertEquals(false, (api.lookUpArtist("Sabrina") as ArtistLookup.Found).exact)
        // Answered, and nobody on the shelf is Bon Iver: remembered by the caller as not there
        assertEquals(ArtistLookup.NotFound, api.lookUpArtist("Bon Iver"))
        // No answer at all: to be tried again, never remembered
        assertEquals(ArtistLookup.NoAnswer, api.lookUpArtist("Nobody"))
        assertEquals(ArtistLookup.NotFound, api.lookUpArtist(" "))
    }
}
