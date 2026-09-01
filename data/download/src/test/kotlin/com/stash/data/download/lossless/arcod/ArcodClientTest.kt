package com.stash.data.download.lossless.arcod

import io.mockk.coEvery
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
 * Unit tests for [ArcodClient] against a [MockWebServer]. The injected
 * [ArcodAuthInterceptor] is built over a mocked store whose `session()` returns
 * null, so it attaches no header (harmless) and never hits a real DataStore.
 */
class ArcodClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ArcodClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val store = mockk<ArcodCredentialStore>(relaxed = true)
        coEvery { store.session() } returns null
        val refresher = mockk<ArcodTokenRefresher>(relaxed = true)
        val interceptor = ArcodAuthInterceptor(store, refresher)
        client = ArcodClient(
            sharedClient = OkHttpClient(),
            authInterceptor = interceptor,
        ).apply {
            baseUrl = server.url("/api").toString().trimEnd('/')
            // Neutral mock base — the real private base lives in BuildConfig and is
            // never embedded here. Asserts only that trackId + quality are appended.
            streamBaseUrl = server.url("/strm").toString().trimEnd('/')
            // The /v2/stash routes issued to Stash: search + stream both live here.
            stashBaseUrl = server.url("/v2/stash").toString().trimEnd('/')
            // CI builds this module with an EMPTY BuildConfig.ARCOD_STASH_KEY, which
            // makes `isConfigured` false and every streamUrl call return null before
            // it reaches the server. Set the seam so these tests exercise real code
            // in CI instead of passing (or failing) vacuously.
            stashKey = TEST_STASH_KEY
        }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `search returns parsed items`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_BODY))

        val items = client.search("Ja Rule Murderers")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // The operator moved Stash to /v2/stash/* — the old generic routes are
        // explicitly not for us, so a regression back to get-music must fail here.
        assertTrue(request.path!!.startsWith("/v2/stash/search"))
        assertTrue(request.path!!.contains("q="))
        assertTrue(request.path!!.contains("offset=0"))

        assertEquals(1, items.size)
        assertEquals(8767428L, items[0].id)
        assertEquals("0093624804567", items[0].album?.id)
        assertEquals("USAT20300456", items[0].isrc)
    }

    @Test fun `search returns empty on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertTrue(client.search("x").isEmpty())
    }

    @Test fun `createJob posts to v2 downloads and parses pending job`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"abc","status":"pending","progress":0}"""),
        )

        val job = client.createJob(SAMPLE_REQUEST)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/v2/downloads"))
        assertNotNull(job)
        assertEquals("abc", job!!.id)
        assertEquals("pending", job.status)
    }

    @Test fun `pollStatus returns completed job after pending polls`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PENDING_BODY))
        server.enqueue(MockResponse().setResponseCode(200).setBody(PENDING_BODY))
        server.enqueue(MockResponse().setResponseCode(200).setBody(COMPLETED_BODY))

        val job = client.pollStatus("abc", timeoutMs = 2000L, intervalMs = 10L)

        assertNotNull(job)
        assertEquals("completed", job!!.status)
        assertNotNull(job.downloadUrl)
    }

    @Test fun `pollStatus returns null on error status`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"x","status":"error","error":"nope"}"""),
        )

        assertNull(client.pollStatus("x", timeoutMs = 2000L, intervalMs = 10L))
    }

    @Test fun `pollStatus returns null on timeout without hanging`() = runTest {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200).setBody(PENDING_BODY)) }

        assertNull(client.pollStatus("x", timeoutMs = 50L, intervalMs = 10L))
    }

    @Test fun `createJob throws on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        try {
            client.createJob(SAMPLE_REQUEST)
            fail("expected ArcodRateLimitedException")
        } catch (e: ArcodRateLimitedException) {
            // expected
        }
    }

    @Test fun `streamUrl appends trackId and quality to the stash stream route and parses plain-text url`() = runTest {
        val url = "https://dl.arcod.xyz/stream/abc.flac?token=xyz"
        server.enqueue(MockResponse().setResponseCode(200).setBody(url))

        val result = client.streamUrl(8767428L, 27)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/stash/stream/8767428?quality=27", request.path)
        assertNotNull(result)
        assertEquals(url, result!!.url)
        assertNull(result.expiresInSec)
    }

    /**
     * The integration key is what separates a 403 from a real answer on the
     * `/v2/stash` routes, so a request that forgets it is silently useless.
     * Only asserted when the build actually carries a key (an unconfigured
     * build legitimately omits the header).
     */
    @Test fun `stash requests carry the X-Stash-Key header when configured`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_BODY))
        client.search("Ja Rule Murderers")
        // Asserted unconditionally now that `stashKey` is a seam: the old
        // BuildConfig branch made this test assert NOTHING in CI.
        assertEquals(TEST_STASH_KEY, server.takeRequest().getHeader("X-Stash-Key"))
    }

    @Test fun `streamUrl parses flat json url`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"url":"https://dl.arcod.xyz/s/x.flac"}"""),
        )

        val result = client.streamUrl(1L, 6)

        assertEquals("https://dl.arcod.xyz/s/x.flac", result!!.url)
        assertNull(result.expiresInSec)
    }

    @Test fun `streamUrl parses enveloped json with expiresIn`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":{"url":"https://dl.arcod.xyz/s/y.flac","expiresIn":120}}"""),
        )

        val result = client.streamUrl(1L, 7)

        assertEquals("https://dl.arcod.xyz/s/y.flac", result!!.url)
        assertEquals(120, result.expiresInSec)
    }

    @Test fun `streamUrl parses enveloped json with string expiresIn`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":{"url":"https://dl.arcod.xyz/s/z.flac","expiresIn":"120"}}"""),
        )

        val result = client.streamUrl(1L, 27)

        assertEquals("https://dl.arcod.xyz/s/z.flac", result!!.url)
        assertEquals(120, result.expiresInSec)
    }

    @Test fun `streamUrl throws on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        try {
            client.streamUrl(1L, 27)
            fail("expected ArcodRateLimitedException")
        } catch (_: ArcodRateLimitedException) {
            // expected
        }
    }

    @Test fun `streamUrl returns null on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertNull(client.streamUrl(1L, 27))
    }

    @Test fun `streamUrl returns null on unparseable body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not a url or json {"))
        assertNull(client.streamUrl(1L, 27))
    }

    private companion object {
        const val TEST_STASH_KEY = "test-stash-key"
        const val QUOTA_BODY = """{"error":"Daily quota reached","resetAt":"midnight UTC"}"""
        /** Arbitrary fixed instant, mid-UTC-day so the midnight boundary is unambiguous. */
        const val FIXED_NOW = 1_788_200_000_000L

        val SAMPLE_REQUEST = ArcodJobRequest(
            albumId = "0093624804567",
            trackId = "8767428",
            albumTitle = "Blood In My Eye",
            artistName = "Ja Rule",
            artistId = "12345",
            coverUrl = "https://img.arcod.xyz/large.jpg",
            releaseDate = "2003-11-04",
            tracksCount = 13,
        )

        val SEARCH_BODY = """
            {
              "success": true,
              "data": {
                "tracks": {
                  "items": [
                    {
                      "id": 8767428,
                      "title": "Murderers (Album Version)",
                      "isrc": "USAT20300456",
                      "duration": 243,
                      "maximum_bit_depth": 24,
                      "performer": { "name": "Ja Rule", "id": 12345 },
                      "album": { "id": "0093624804567", "title": "Blood In My Eye" }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        const val PENDING_BODY = """{"id":"abc","status":"pending","progress":40}"""

        val COMPLETED_BODY = """
            {"id":"abc","status":"completed","progress":100,"fileName":"x.flac","fileSize":16416910,"downloadUrl":"https://dl.arcod.xyz/downloads/abc/x.flac"}
        """.trimIndent()
    }
    // --- daily-quota gate -------------------------------------------------
    //
    // Driven through `search` deliberately: `streamUrl` self-gates on
    // BuildConfig.ARCOD_STASH_KEY, which is EMPTY in CI unit tests, so a gate
    // test written against it would pass vacuously without ever hitting the net.

    @Test fun `daily quota 429 blocks later calls without another request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody(QUOTA_BODY))
        client.nowMs = { FIXED_NOW }

        try {
            client.search("first")
            fail("expected ArcodRateLimitedException")
        } catch (e: ArcodRateLimitedException) {
            // expected
        }
        assertEquals(1, server.requestCount)

        // Second call must throw from the gate, spending NO request. This is the
        // whole point: a spent quota used to cost a guaranteed-429 round trip on
        // every single tap until midnight.
        try {
            client.search("second")
            fail("expected ArcodRateLimitedException from the gate")
        } catch (e: ArcodRateLimitedException) {
            // expected
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `quota gate reopens after the next UTC midnight`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody(QUOTA_BODY))
        var now = FIXED_NOW
        client.nowMs = { now }
        try { client.search("first") } catch (e: ArcodRateLimitedException) { /* expected */ }

        // One ms past the next UTC midnight: the gate is open and the call goes out.
        now = (FIXED_NOW / 86_400_000L + 1) * 86_400_000L + 1
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_BODY))
        assertTrue(client.search("after midnight").isNotEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun `a 429 that says quota but not daily is a burst, not a day-long lockout`() = runTest {
        // The discriminator is the reset PERIOD ("daily"), not the kind of limit
        // ("quota"): a momentary throttle worded like this must not cost the rest
        // of the day.
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate quota exceeded, retry in 60s"}"""))
        var now = FIXED_NOW
        client.nowMs = { now }
        try { client.search("first") } catch (e: ArcodRateLimitedException) { /* expected */ }

        now = FIXED_NOW + 61_000L
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_BODY))
        assertTrue(client.search("after cooldown").isNotEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun `non-quota 429 backs off only briefly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"slow down"}"""))
        var now = FIXED_NOW
        client.nowMs = { now }
        try { client.search("first") } catch (e: ArcodRateLimitedException) { /* expected */ }

        // A burst throttle must NOT cost the rest of the day.
        now = FIXED_NOW + 61_000L
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_BODY))
        assertTrue(client.search("after cooldown").isNotEmpty())
        assertEquals(2, server.requestCount)
    }

}
