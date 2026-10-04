package com.stash.data.download.lossless.qbdlx

import com.stash.core.data.discography.QobuzAlbumUnavailableException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [QobuzAlbumFetcherImpl]. [QbdlxApiClient] is MockK'd; the mocked
 * getAlbum returns the real parsed `album_loveless.json` fixture so the
 * Qobuz→AlbumDetail mapping runs against a faithful response.
 */
class QobuzAlbumFetcherImplTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private fun fixture(n: String) = javaClass.classLoader!!.getResourceAsStream("qbdlx/$n")!!.reader().readText()

    private val apiClient: QbdlxApiClient = mockk()

    private fun fetcher() = QobuzAlbumFetcherImpl(apiClient)

    @Test
    fun `maps qobuz album to AlbumDetail with blank videoIds and real durations`() = runTest {
        val album = json.decodeFromString<QbdlxAlbumDetailResponse>(fixture("album_loveless.json"))
        coEvery { apiClient.getAlbum("123") } returns album

        val detail = fetcher().getAlbum("123")

        assertEquals("loveless", detail.title.lowercase())
        assertTrue(detail.tracks.isNotEmpty())
        assertTrue(detail.tracks.all { it.videoId == "" })
        assertTrue(detail.tracks.all { it.durationSeconds > 0 })
        assertTrue(detail.moreByArtist.isEmpty())
    }

    /**
     * #481: outside the countries Qobuz sells in, album/get answers 404 for every
     * album Home lists. That has to reach the album screen as its own type, so the
     * screen can look for the album elsewhere instead of offering a Retry that can
     * never work.
     */
    @Test
    fun `a 404 from Qobuz means the album isn't sold here`() = runTest {
        val notFound = QbdlxApiException(404, """{"status":"error","code":404,"message":"No result matching given argument"}""")
        coEvery { apiClient.getAlbum("123") } throws notFound

        val e = runCatching { fetcher().getAlbum("123") }.exceptionOrNull()

        assertTrue("got $e", e is QobuzAlbumUnavailableException)
        assertEquals("123", (e as QobuzAlbumUnavailableException).albumId)
        assertSame(notFound, e.cause)
    }

    /** A server hiccup or a dropped connection can still be retried — only a 404 changes type. */
    @Test
    fun `other failures pass through unchanged`() = runTest {
        val serverError = QbdlxApiException(503, "busy")
        coEvery { apiClient.getAlbum("123") } throws serverError
        assertSame(serverError, runCatching { fetcher().getAlbum("123") }.exceptionOrNull())

        val offline = IOException("Unable to resolve host www.qobuz.com")
        coEvery { apiClient.getAlbum("123") } throws offline
        assertSame(offline, runCatching { fetcher().getAlbum("123") }.exceptionOrNull())
    }

    @Test
    fun `maps qobuz playlist to AlbumDetail with curator as artist`() = runTest {
        val playlist = json.decodeFromString<QbdlxPlaylistDetailResponse>(
            """{"id":67048110,"name":"Brazilcore II","owner":{"name":"Qobuz France"},
               "images300":["https://img/300.jpg"],
               "tracks":{"items":[
                 {"id":1,"title":"Funky Tamborim","performer":{"name":"Tania Maria"},
                  "duration":195,"album":{"title":"Love Explosion","image":{"large":"AL"}}}]}}""",
        )
        coEvery { apiClient.getPlaylist("67048110") } returns playlist

        val detail = fetcher().getPlaylist("67048110")

        assertEquals("Brazilcore II", detail.title)
        assertEquals("Qobuz France", detail.artist)                 // curator
        assertEquals("https://img/300.jpg", detail.thumbnailUrl)
        assertEquals("Tania Maria", detail.tracks.single().artist)
        assertEquals("AL", detail.tracks.single().thumbnailUrl)     // per-track album art
        assertTrue(detail.tracks.all { it.videoId == "" })
    }
}
