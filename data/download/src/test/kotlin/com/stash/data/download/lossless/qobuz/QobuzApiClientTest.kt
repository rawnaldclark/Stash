package com.stash.data.download.lossless.qobuz

import com.stash.data.download.lossless.squid.SquidWtfCaptchaInterceptor
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [QobuzApiClient] hitting a [MockWebServer] that emulates
 * the squid.wtf proxy. Verifies request shape (paths, params, optional
 * Token-Country header), envelope unwrapping, and error mapping.
 */
class QobuzApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: QobuzApiClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        // Strip trailing slash so client appends paths cleanly.
        // Mockk-relaxed the captcha interceptor — these tests don't
        // exercise the cookie path, and we don't want the
        // interceptor's DataStore-backed init firing in unit tests.
        // Override `httpClient` directly so the interceptor doesn't
        // wrap the MockWebServer-bound client.
        val rawClient = OkHttpClient()
        client = QobuzApiClient(
            sharedClient = rawClient,
            captchaInterceptor = mockk<SquidWtfCaptchaInterceptor>(relaxed = true),
        ).apply {
            httpClient = rawClient
            baseUrl = server.url("/api").toString().removeSuffix("/")
        }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    // ── search() ─────────────────────────────────────────────────────────

    @Test fun `search builds get-music URL with q and offset, no auth headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_SEARCH_BODY))

        client.search(query = "Frusciante", offset = 5)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.startsWith("/api/get-music"))
        assertTrue(request.path!!.contains("q=Frusciante"))
        assertTrue(request.path!!.contains("offset=5"))
        // squid.wtf expects no auth — proves we're not accidentally
        // sending direct-Qobuz headers.
        assertNull(request.getHeader("X-App-Id"))
        assertNull(request.getHeader("X-User-Auth-Token"))
        assertNull(request.getHeader("Token-Country"))
    }

    @Test fun `search adds Token-Country header when supplied`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_SEARCH_BODY))

        client.search(query = "x", tokenCountry = "FR")

        val request = server.takeRequest()
        assertEquals("FR", request.getHeader("Token-Country"))
    }

    @Test fun `search ignores blank Token-Country`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_SEARCH_BODY))

        client.search(query = "x", tokenCountry = "  ")

        val request = server.takeRequest()
        assertNull(request.getHeader("Token-Country"))
    }

    @Test fun `search unwraps envelope and parses tracks`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_SEARCH_ENVELOPE))

        val data = client.search("Karma Police")

        assertNotNull(data.tracks)
        val items = data.tracks!!.items
        assertEquals(1, items.size)
        assertEquals(98765L, items[0].id)
        assertEquals("Karma Police", items[0].title)
        assertEquals("Radiohead", items[0].performer?.name)
        assertEquals(16, items[0].maximumBitDepth)
        assertEquals(44.1f, items[0].maximumSamplingRate)
        assertTrue(items[0].streamable)
    }

    // ── getFileUrl() ────────────────────────────────────────────────────

    @Test fun `getFileUrl builds download-music URL with track_id and quality`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_DOWNLOAD_ENVELOPE))

        client.getFileUrl(trackId = 12345L, quality = QobuzQuality.FLAC_HIRES_192)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/download-music"))
        assertTrue(request.path!!.contains("track_id=12345"))
        assertTrue(request.path!!.contains("quality=27"))
        // No signing — squid.wtf doesn't accept request_sig / request_ts.
        assertTrue("must not send request_sig", !request.path!!.contains("request_sig"))
        assertTrue("must not send request_ts", !request.path!!.contains("request_ts"))
    }

    @Test fun `getFileUrl returns the signed CDN url from the envelope`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_DOWNLOAD_ENVELOPE))

        val result = client.getFileUrl(trackId = 12345L)

        assertEquals("https://streaming-qobuz-std.akamaized.net/abc.flac?sig=signed", result.url)
    }

    // ── Errors ──────────────────────────────────────────────────────────

    @Test fun `non-2xx response throws QobuzApiException with status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":"region locked"}"""))

        try {
            client.getFileUrl(trackId = 1L)
            fail("expected QobuzApiException")
        } catch (e: QobuzApiException) {
            assertEquals(403, e.status)
            assertEquals("region locked", e.message)
        }
    }

    @Test fun `non-2xx with non-JSON body still throws with status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal server error"))

        try {
            client.search("anything")
            fail("expected QobuzApiException")
        } catch (e: QobuzApiException) {
            assertEquals(500, e.status)
        }
    }

    @Test fun `2xx with success false throws with parsed error message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":false,"error":"missing q parameter"}"""),
        )

        try {
            client.search("")
            fail("expected QobuzApiException")
        } catch (e: QobuzApiException) {
            assertEquals(200, e.status)
            assertEquals("missing q parameter", e.message)
        }
    }

    companion object {
        // squid.wtf envelope: {success, data, error}. data is the Qobuz
        // catalog/search payload; we model only the fields we need.
        private const val EMPTY_SEARCH_BODY =
            """{"success":true,"data":{"query":"x","tracks":{"total":0,"items":[]}}}"""

        private const val SAMPLE_SEARCH_ENVELOPE = """
        {
          "success": true,
          "data": {
            "query": "Karma Police",
            "tracks": {
              "total": 1,
              "limit": 10,
              "offset": 0,
              "items": [
                {
                  "id": 98765,
                  "title": "Karma Police",
                  "duration": 261,
                  "isrc": "GBAYE9700053",
                  "performer": { "id": 12, "name": "Radiohead" },
                  "album": { "id": "ok-computer-id", "title": "OK Computer", "tracks_count": 12 },
                  "maximum_bit_depth": 16,
                  "maximum_sampling_rate": 44.1,
                  "streamable": true,
                  "hires": false
                }
              ]
            }
          }
        }
        """

        private const val SAMPLE_DOWNLOAD_ENVELOPE = """
        {
          "success": true,
          "data": { "url": "https://streaming-qobuz-std.akamaized.net/abc.flac?sig=signed" }
        }
        """
    }
}
