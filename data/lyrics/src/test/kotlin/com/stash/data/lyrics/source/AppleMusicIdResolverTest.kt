package com.stash.data.lyrics.source

import com.stash.core.common.Clock
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** One iTunes search per song: remembered answers, and the throw/quiet split between Apple's source and Spicy. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppleMusicIdResolverTest {

    private val hit = """{"results":[{"trackId":42,"trackName":"Song","artistName":"Artist","trackTimeMillis":200000}]}"""
    private lateinit var server: MockWebServer
    private val searches = AtomicInteger()
    private var status = 200
    private var body = hit
    private var now = 0L
    private lateinit var resolver: AppleMusicIdResolver

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                searches.incrementAndGet()
                return MockResponse().setResponseCode(status).setBody(body)
            }
        }
        server.start()
        resolver = AppleMusicIdResolver(
            OkHttpClient(), "test", server.url("/").toString(), Dns.SYSTEM,
            object : Clock { override fun now() = now },
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun query() = LyricsQuery(1L, "Song", "Artist", null, null, 200_000L, null, interactive = true)

    @Test fun `an answer is remembered, so a song is searched once`() = runTest {
        assertEquals("42", resolver.find(query()))
        assertEquals("42", resolver.resolve(query()))
        assertEquals(1, searches.get())
    }

    @Test fun `a failed search throws from find, is just null from resolve, and is never remembered`() = runTest {
        status = 500
        assertThrows(AppleTtmlLyricsSource.HttpStatusException::class.java) { resolver.find(query()) }
        assertNull(resolver.resolve(query()))
        status = 200
        assertEquals("42", resolver.find(query()))
        assertEquals(3, searches.get())
    }

    @Test fun `a definitive miss is remembered for ten minutes`() = runTest {
        body = """{"results":[]}"""
        assertNull(resolver.find(query()))
        assertNull(resolver.find(query()))
        assertEquals(1, searches.get())
        now += AppleMusicIdResolver.MISS_TTL_MS
        assertNull(resolver.find(query()))
        assertEquals(2, searches.get())
    }

    @Test fun `cached answers only what a search already found, without asking iTunes`() = runTest {
        assertNull("not searched yet", resolver.cached(query()))
        assertEquals(0, searches.get())
        assertEquals("42", resolver.find(query()))
        assertEquals("42", resolver.cached(query()))
        assertEquals(1, searches.get())
    }

    @Test fun `an iTunes 429 is thrown from resolve too, so an upgrade pass stops`() = runTest {
        status = 429
        val thrown = runCatching { resolver.resolve(query()) }.exceptionOrNull()
        assertEquals(429, (thrown as AppleTtmlLyricsSource.HttpStatusException).code)
    }

    @Test fun `a title that is only brackets matches nothing, rather than any song by the artist`() = runTest {
        val intro = LyricsQuery(1L, "(Intro)", "Artist", null, null, 200_000L, null, interactive = true)
        assertNull(resolver.find(intro))
        assertEquals("not worth a request", 0, searches.get())
    }
}
