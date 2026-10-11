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

    @Before fun setUp() {
        cacheDir.mkdirs()
        every { fileOrganizer.getAlbumArtDir() } returns cacheDir
        subject = AlbumArtCache(fileOrganizer, httpClient)
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
    @Test fun `yt-music art is fetched at the 1200 embed size whatever size was stored`() = runTest {
        val call = stubHttp200(ByteArray(128))
        val requests = mutableListOf<Request>()
        every { httpClient.newCall(capture(requests)) } returns call
        subject.resolveArt(stubTrack(albumArtUrl = "https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj"))
        assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200", requests.single().url.toString())
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
}
