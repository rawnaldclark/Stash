package com.stash.data.download.files

import com.stash.core.model.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AlbumArtCacheTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "albumart-test-${System.nanoTime()}")
    private val fileOrganizer: FileOrganizer = mockk()
    private val httpClient: OkHttpClient = mockk()
    private lateinit var subject: AlbumArtCache

    /**
     * Stands in for the Android decoder: the "image" is fitted as-is, and its long side is
     * whatever [longSideOf] says for those bytes (default: unknown size, as for undecodable art).
     */
    private var longSideOf: (ByteArray) -> Int = { 0 }
    private val scaler = object : EmbedArtScaler() {
        override suspend fun fit(bytes: ByteArray, maxPx: Int) = EmbedArtScaler.Fitted(bytes, longSideOf(bytes))
    }

    @Before fun setUp() {
        cacheDir.mkdirs()
        every { fileOrganizer.getAlbumArtDir() } returns cacheDir
        subject = AlbumArtCache(fileOrganizer, httpClient, scaler)
    }

    @After fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test fun `returns null when track has no albumArtUrl`() = runTest {
        val result = subject.resolveArt(stubTrack(albumArtUrl = null))
        assertNull(result)
    }

    @Test fun `fetches and caches a fresh URL`() = runTest {
        val body = ByteArray(1024) { 0xFF.toByte() }
        stubHttp200(body)
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/abc"))
        assertNotNull(result)
        assertEquals(1024L, result!!.length())
    }

    @Test fun `second call hits cache`() = runTest {
        val body = ByteArray(2048)
        stubHttp200(body)
        subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/abc"))
        subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/abc"))
        verify(exactly = 1) { httpClient.newCall(any()) }
    }

    @Test fun `404 returns null`() = runTest {
        stubHttp(code = 404, body = ByteArray(0))
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/missing"))
        assertNull(result)
    }

    @Test fun `non-image content-type returns null`() = runTest {
        stubHttp(code = 200, body = ByteArray(64), contentType = "text/html")
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/wrong"))
        assertNull(result)
    }

    @Test fun `zero-byte response returns null`() = runTest {
        stubHttp200(body = ByteArray(0))
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/empty"))
        assertNull(result)
    }

    @Test fun `same album URL with different size suffixes shares the cache`() = runTest {
        val body = ByteArray(512)
        stubHttp200(body)
        subject.resolveArt(stubTrack(albumArtUrl = "https://lh3.googleusercontent.com/abc=w64-h64"))
        subject.resolveArt(stubTrack(albumArtUrl = "https://lh3.googleusercontent.com/abc=w640-h640"))
        // Both URLs canonicalise to the same 640px form so we should fetch once
        verify(exactly = 1) { httpClient.newCall(any()) }
    }

    @Test fun `yt-music multi-suffix variants all canonicalise to the same cache entry`() = runTest {
        val body = ByteArray(256)
        stubHttp200(body)
        val urls = listOf(
            "https://lh3.googleusercontent.com/abc=w64-h64",
            "https://lh3.googleusercontent.com/abc=w640-h640",
            "https://lh3.googleusercontent.com/abc=w64-h64-l50-rj",
            "https://lh3.googleusercontent.com/abc=w300-h300-l90-rj",
            "https://lh3.googleusercontent.com/abc=w120-h120-p-l90-rj",
            "https://lh3.googleusercontent.com/abc=w120-h120-p-rj",
        )
        urls.forEach { subject.resolveArt(stubTrack(albumArtUrl = it)) }
        // All variants canonicalise to the same URL → exactly one HTTP fetch
        verify(exactly = 1) { httpClient.newCall(any()) }
    }

    // The embedded cover used to be fetched at 640, shrinking the 1024px covers the library
    // stores (and the ~1400px originals behind them) in every downloaded file.
    @Test fun `yt-music art is fetched as a 1200 jpeg whatever size was stored`() = runTest {
        val call = stubHttp200(ByteArray(128))
        val requests = mutableListOf<Request>()
        every { httpClient.newCall(capture(requests)) } returns call
        subject.resolveArt(stubTrack(albumArtUrl = "https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj"))
        assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj", requests.single().url.toString())
    }

    // Qobuz, Last.fm and Spotify embeds used to be whatever was stored (600, 770, 640). They now
    // ask for the original and the scaler fits it within 1200; the stored URL stays the floor.

    @Test fun `qobuz embeds the max variant instead of the stored 600`() = runTest {
        val big = ByteArray(300) { 1 }
        val requests = stubUrls(mapOf(QOBUZ_MAX to (200 to big), QOBUZ_600 to (200 to ByteArray(10))))
        val file = subject.resolveArt(stubTrack(albumArtUrl = QOBUZ_600))
        assertEquals(listOf(QOBUZ_MAX), requests)
        assertEquals(300L, file!!.length())
    }

    @Test fun `a missing or failing bigger variant falls back to the stored url`() = runTest {
        val stored = ByteArray(60) { 2 }
        val requests = stubUrls(mapOf(QOBUZ_MAX to (503 to ByteArray(0)), QOBUZ_600 to (200 to stored)))
        val file = subject.resolveArt(stubTrack(albumArtUrl = QOBUZ_600))
        assertEquals(listOf(QOBUZ_MAX, QOBUZ_600), requests)
        assertEquals(60L, file!!.length())
    }

    @Test fun `a lastfm original smaller than the stored 770 embeds the stored url`() = runTest {
        val original = ByteArray(35) { 3 }
        val rendered770 = ByteArray(77) { 4 }
        longSideOf = { if (it.size == 35) 350 else 770 }
        val requests = stubUrls(mapOf(LASTFM_ORIG to (200 to original), LASTFM_770 to (200 to rendered770)))
        val file = subject.resolveArt(stubTrack(albumArtUrl = LASTFM_770))
        assertEquals(listOf(LASTFM_ORIG, LASTFM_770), requests)
        assertEquals(77L, file!!.length())
    }

    @Test fun `a lastfm original at least the stored size is embedded`() = runTest {
        val original = ByteArray(120) { 5 }
        longSideOf = { 1200 }
        val requests = stubUrls(mapOf(LASTFM_ORIG to (200 to original), LASTFM_770 to (200 to ByteArray(7))))
        val file = subject.resolveArt(stubTrack(albumArtUrl = LASTFM_770))
        assertEquals(listOf(LASTFM_ORIG), requests)
        assertEquals(120L, file!!.length())
    }

    @Test fun `when both the bigger variant and the stored url fail there is no art`() = runTest {
        stubUrls(mapOf(QOBUZ_MAX to (404 to ByteArray(0)), QOBUZ_600 to (500 to ByteArray(0))))
        assertNull(subject.resolveArt(stubTrack(albumArtUrl = QOBUZ_600)))
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `network failure returns null and leaves no cache file`() = runTest {
        val call: Call = mockk()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } throws java.io.IOException("connection reset")

        val track = stubTrack(albumArtUrl = "https://i.scdn.co/image/network-error")
        val result = subject.resolveArt(track)

        assertNull(result)
        // No cache file should remain
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private fun stubTrack(albumArtUrl: String?) = Track(
        id = 1, title = "T", artist = "A", albumArtUrl = albumArtUrl,
    )

    private fun stubHttp200(body: ByteArray): Call = stubHttp(200, body, "image/jpeg")

    private fun stubHttp(code: Int, body: ByteArray, contentType: String = "image/jpeg"): Call {
        val call = mockk<Call>()
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.test/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
        every { httpClient.newCall(any()) } returns call
        every { call.execute() } returns response
        return call
    }

    /** Answers each URL in [responses] with its (code, body); returns the URLs requested, in order. */
    private fun stubUrls(responses: Map<String, Pair<Int, ByteArray>>): List<String> {
        val requested = mutableListOf<String>()
        every { httpClient.newCall(any()) } answers {
            val request = firstArg<Request>()
            val url = request.url.toString()
            requested += url
            val (code, body) = responses[url] ?: (404 to ByteArray(0))
            val call = mockk<Call>()
            every { call.execute() } returns Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .body(body.toResponseBody("image/jpeg".toMediaType()))
                .build()
            call
        }
        return requested
    }

    private companion object {
        const val QOBUZ_600 = "https://static.qobuz.com/images/covers/25/40/0060254794025_600.jpg"
        const val QOBUZ_MAX = "https://static.qobuz.com/images/covers/25/40/0060254794025_max.jpg"
        const val LASTFM_770 = "https://lastfm-img.freetls.fastly.net/i/u/770x0/c9ebed7e766b45dabedaa8ee759b5181.jpg"
        const val LASTFM_ORIG = "https://lastfm-img.freetls.fastly.net/i/u/ar0/c9ebed7e766b45dabedaa8ee759b5181.jpg"
    }
}
