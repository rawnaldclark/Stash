package com.stash.data.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
 * which [YTMusicApiClient.getArtist] follows and the one-browse page must not.
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
        for (shelf in listOf(profile.albums, profile.singles)) {
            val years = shelf.map { it.year!!.toInt() }
            assertEquals(years.sortedDescending(), years)
        }
    }
}
