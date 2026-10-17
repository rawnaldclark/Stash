package com.stash.data.lyrics.source

import com.stash.core.common.Clock
import java.io.InterruptedIOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cache-first lookup and the skip window after repeated live timeouts. Robolectric for real org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppleTtmlLyricsSourceTest {

    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" """ +
        """itunes:timing="Word"><body><div><p begin="1.000" end="2.000"><span begin="1.000" end="2.000">Hi</span></p></div></body></tt>"""
    private val search = """{"results":[{"trackId":42,"trackName":"Song","artistName":"Artist","trackTimeMillis":200000}]}"""

    private lateinit var server: MockWebServer
    private var cacheHit = false
    private var liveTimesOut = false
    private var liveTlsFails = false
    private var liveCalls = 0
    private var now = 0L
    private lateinit var source: AppleTtmlLyricsSource

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/search") -> MockResponse().setBody(search)
                request.path!!.startsWith("/apple-music/cache/42") ->
                    if (cacheHit) MockResponse().setHeader("Content-Type", "application/xml").setBody(ttml)
                    else MockResponse().setResponseCode(404).setBody("""{"detail":"Track '42' is not in the Google Drive cache."}""")
                request.path!!.startsWith("/apple-music/lyrics") -> MockResponse().setBody("""{"type":"TTML","ttml":${org.json.JSONObject.quote(ttml)}}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            if (chain.request().url.encodedPath.startsWith("/apple-music/lyrics")) {
                liveCalls++
                if (liveTimesOut) throw InterruptedIOException("timeout")
                if (liveTlsFails) throw javax.net.ssl.SSLException("Unable to parse TLS packet header")
            }
            chain.proceed(chain.request())
        }).build()
        val base = server.url("/").toString()
        source = AppleTtmlLyricsSource(client, "test", base, base, object : Clock { override fun now() = now }, lyricsDns = Dns.SYSTEM)
    }

    @After fun tearDown() { server.shutdown() }

    private fun query() = LyricsQuery(1L, "Song", "Artist", null, null, 200_000L, null, interactive = true)

    @Test fun `a cache hit is used and the live endpoint is not called`() = runTest {
        cacheHit = true
        val result = source.resolve(query())
        assertNotNull(result!!.ttml)
        assertEquals(0, liveCalls)
    }

    @Test fun `a cache miss falls back to the live endpoint`() = runTest {
        val result = source.resolve(query())
        assertNotNull(result!!.ttml)
        assertEquals(1, liveCalls)
    }

    @Test fun `three live timeouts skip Apple for ten minutes, then it tries again`() = runTest {
        liveTimesOut = true
        repeat(3) { assertThrows(InterruptedIOException::class.java) { runBlocking { source.resolve(query()) } } }
        assertThrows(AppleTtmlLyricsSource.AppleSkippedException::class.java) {
            runBlocking { source.resolve(query()) }
        }
        assertEquals(3, liveCalls)

        // The cache is still asked during the window.
        cacheHit = true
        assertNotNull(source.resolve(query()))
        cacheHit = false

        now += AppleTtmlLyricsSource.SKIP_WINDOW_MS
        liveTimesOut = false
        assertNotNull(source.resolve(query()))
        assertEquals(4, liveCalls)
    }

    @Test fun `three fast TLS failures open the skip window too, not only timeouts`() = runTest {
        // The PR #605 network: a rewritten DNS answer made every handshake fail at once with an
        // SSLException, which never counted, so the background worker kept hammering the host.
        liveTlsFails = true
        repeat(AppleTtmlLyricsSource.SKIP_AFTER_TIMEOUTS) {
            assertThrows(javax.net.ssl.SSLException::class.java) { runBlocking { source.resolve(query()) } }
        }
        val callsBefore = liveCalls
        assertThrows(AppleTtmlLyricsSource.AppleSkippedException::class.java) { runBlocking { source.resolve(query()) } }
        assertEquals(callsBefore, liveCalls)
    }

    @Test fun `a success resets the timeout count`() = runTest {
        liveTimesOut = true
        repeat(2) { assertThrows(InterruptedIOException::class.java) { runBlocking { source.resolve(query()) } } }
        liveTimesOut = false
        assertNotNull(source.resolve(query()))
        liveTimesOut = true
        repeat(2) { assertThrows(InterruptedIOException::class.java) { runBlocking { source.resolve(query()) } } }
        // Only 2 in a row since the success: the next call still goes live.
        assertThrows(InterruptedIOException::class.java) { runBlocking { source.resolve(query()) } }
        assertEquals(6, liveCalls)
    }
}
