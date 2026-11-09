package com.stash.data.lyrics.source

import com.stash.core.common.Clock
import com.stash.data.lyrics.parser.TtmlParser
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InterruptedIOException

/**
 * Spicy Lyrics (#837): how each answer type maps onto the lyrics pipeline (Syllable -> TTML the
 * syllable renderer reads, Line -> LRC, Static -> a miss), what opens the skip window, and when
 * credits are kept. Robolectric for real org.json + android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpicyLyricsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** The proxy's answer for every track; unset = 404. */
    @Volatile private var answer: MockResponse? = null
    /** Requests that left the source (counted before the network, so a time-out counts too). */
    @Volatile private var calls = 0
    @Volatile private var timeOut = false
    private var now = 1_700_000_000_000L
    private val clock = object : Clock { override fun now() = now }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                answer ?: MockResponse().setResponseCode(404)
        }
        server.start()
        client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls++
            if (timeOut) throw InterruptedIOException("timeout")
            chain.proceed(chain.request())
        }).build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun source(baseUrl: String = server.url("/spicy").toString()) =
        SpicyLyricsSource(client, "test", baseUrl = baseUrl, clock = clock, lyricsDns = Dns.SYSTEM)

    private fun query(
        spotifyTrackId: String? = TRACK_ID,
        isrc: String? = null,
        interactive: Boolean = true,
        searchForIds: Boolean = false,
    ) = LyricsQuery(
        trackId = 1L, title = "Song", artist = "Artist", album = null, albumArtist = null,
        durationMs = 200_000L, youtubeVideoId = null, isrc = isrc, interactive = interactive, onDemand = true,
        spotifyTrackId = spotifyTrackId, searchForIds = searchForIds,
    )

    private fun serve(body: String, code: Int = 200) {
        answer = MockResponse().setResponseCode(code).setBody(body)
    }

    // Two vocal lines. The first: "Hel"+"lo" glued into one word, then text that needs escaping and
    // a control character XML forbids, plus a background vocal that runs past the lead. The second:
    // the other singer (OppositeAligned), more than an hour in.
    private val syllable = """
        {"Status":200,"Body":{"Type":"Syllable","id":"sl-42","source":"spicy_lyrics","StartTime":1.0,"EndTime":3726.0,
         "UploadAttribution":{
           "Uploader":{"username":"alice","url":"https://spicylyrics.org/u/alice"},
           "Maker":{"username":"bob","url":"http://insecure.example/bob"}},
         "Content":[
          {"Type":"Vocal","OppositeAligned":false,
           "Lead":{"StartTime":1.0,"EndTime":2.5,"Syllables":[
             {"Text":"Hel","StartTime":1.0,"EndTime":1.2,"IsPartOfWord":true},
             {"Text":"lo","StartTime":1.2,"EndTime":1.5,"IsPartOfWord":false},
             {"Text":"R&B","StartTime":1.5,"EndTime":1.8,"IsPartOfWord":false},
             {"Text":"<3","StartTime":1.8,"EndTime":2.0,"IsPartOfWord":false},
             {"Text":"\"quoted\"\u0001","StartTime":2.0,"EndTime":2.5,"IsPartOfWord":false}]},
           "Background":[{"StartTime":2.0,"EndTime":2.8,"Syllables":[
             {"Text":"(oh","StartTime":2.0,"EndTime":2.4,"IsPartOfWord":false},
             {"Text":"yeah)","StartTime":2.4,"EndTime":2.8,"IsPartOfWord":false}]}]},
          {"Type":"Instrumental","StartTime":2.8,"EndTime":3725.0},
          {"Type":"Vocal","OppositeAligned":true,
           "Lead":{"StartTime":3725.5,"EndTime":3726.0,"Syllables":[
             {"Text":"Late","StartTime":3725.5,"EndTime":3726.0,"IsPartOfWord":false}]}}
         ]}}
    """.trimIndent()

    @Test fun `it is an on-demand source`() {
        assertTrue(source().onDemandOnly)
    }

    @Test fun `Syllable becomes word-synced TTML the parser reads, escaped, with duets and background vocals`() = runTest {
        serve(syllable)
        val result = source().resolve(query())!!

        assertEquals(SpicyLyricsSource.SOURCE_ID, result.sourceId)
        assertEquals("sl-42", result.sourceLyricsId)
        val ttml = result.ttml!!
        assertTrue("word-timed, as the upgrade paths require", IsrcTtmlLyricsSource.isWordTimed(ttml))
        val parsed = TtmlParser.parse(ttml)!!
        assertEquals(2, parsed.lines.size)

        val first = parsed.lines[0]
        assertEquals("Hello R&B <3 \"quoted\"", first.lead.text)
        assertEquals(listOf(true, false, false, false, false), first.lead.syllables.map { it.partOfWord })
        assertFalse(first.oppositeAligned)
        assertEquals(1, first.background.size)
        assertEquals("oh yeah", first.background[0].text)
        assertEquals("the line lasts as long as its background vocal", 2_800L, first.lead.endMs)

        val second = parsed.lines[1]
        assertTrue("the other singer", second.oppositeAligned)
        assertEquals("Late", second.lead.text)
        assertEquals("over an hour in", 3_725_500L, second.lead.syllables[0].startMs)
        assertEquals(3_726_000L, second.lead.syllables[0].endMs)

        assertEquals("Hello R&B <3 \"quoted\"\nLate", result.plainText)
        assertEquals("[00:01.00]Hello R&B <3 \"quoted\"\n[62:05.50]Late", result.syncedLrc)
    }

    @Test fun `credits come with Spicy's own catalogue, https links only`() = runTest {
        serve(syllable)
        val credits = source().resolve(query())!!.credits!!
        assertEquals("alice", credits.uploaderName)
        assertEquals("https://spicylyrics.org/u/alice", credits.uploaderUrl)
        assertEquals("bob", credits.makerName)
        assertNull("an http link is dropped, the name stays", credits.makerUrl)
    }

    @Test fun `no credits when another catalogue answered`() = runTest {
        serve(syllable.replace("\"source\":\"spicy_lyrics\"", "\"source\":\"musixmatch\""))
        val result = source().resolve(query())!!
        assertNotNull(result.ttml)
        assertNull(result.credits)
    }

    @Test fun `a JSON null name is no name, not the word null`() = runTest {
        serve(syllable.replace("\"username\":\"alice\"", "\"username\":null"))
        val credits = source().resolve(query())!!.credits!!
        assertNull(credits.uploaderName)
        assertEquals("bob", credits.makerName)
    }

    @Test fun `Line becomes line-synced LRC with no TTML`() = runTest {
        serve(
            """{"Status":200,"Body":{"Type":"Line","source":"spicy_lyrics","Content":[
                {"Type":"Vocal","Text":"First line","StartTime":12.34,"EndTime":15.0},
                {"Type":"Vocal","Text":"   ","StartTime":15.0,"EndTime":16.0},
                {"Type":"Vocal","Text":"Second","StartTime":75.5,"EndTime":80.0}]}}""",
        )
        val result = source().resolve(query())!!
        assertNull(result.ttml)
        assertEquals("[00:12.34]First line\n[01:15.50]Second", result.syncedLrc)
        assertEquals("First line\nSecond", result.plainText)
        assertEquals("no id in the answer: the track id", TRACK_ID, result.sourceLyricsId)
    }

    @Test fun `Static plain text is a miss, so LRCLIB and KuGou still get their turn`() = runTest {
        serve("""{"Status":200,"Body":{"Type":"Static","source":"spicy_lyrics","Lines":[{"Text":"Just words"}]}}""")
        assertNull(source().resolve(query()))
    }

    @Test fun `empty answers are misses that don't pause the source`() = runTest {
        val spicy = source()
        for (body in listOf(
            """{"Status":200,"Body":{"Type":"Syllable","Content":[]}}""",
            """{"Status":200,"Body":{"Type":"Line","Content":[{"Text":""}]}}""",
            """{"Status":404}""",
            "{}",
        )) {
            serve(body)
            assertNull(body, spicy.resolve(query()))
        }
        assertEquals("every one was asked: no skip window", 4, calls)
    }

    @Test fun `a 404 is a miss without the skip window`() = runTest {
        val spicy = source()
        assertNull(spicy.resolve(query()))
        assertNull(spicy.resolve(query()))
        assertEquals(2, calls)
    }

    @Test fun `a 503 is a miss that pauses the source for the skip window`() = runTest {
        assertPausesFor { serve("oops", code = 503) }
    }

    @Test fun `a 429 pauses the source too, and is thrown so an upgrade pass hears about it`() = runTest {
        assertPausesFor(rateLimited = true) { serve("slow down", code = 429) }
    }

    @Test fun `without a Spotify id the track's own ISRC is asked, normalised`() = runTest {
        serve(syllable)
        source().resolve(query(spotifyTrackId = null, isrc = "us-aaa-00-00001"))
        assertEquals("/spicy/v1/lyrics/USAAA0000001", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun `the Spotify id wins over the ISRC`() = runTest {
        serve(syllable)
        source().resolve(query(isrc = "USAAA0000001"))
        assertEquals("/spicy/v1/lyrics/$TRACK_ID", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun `a 400 for an id the proxy doesn't take is a miss without the skip window`() = runTest {
        val spicy = source()
        serve("Bad track id", code = 400)
        assertNull(spicy.resolve(query()))
        serve(syllable)
        assertNotNull("Spicy still answers the next song", spicy.resolve(query()))
        assertEquals(2, calls)
    }

    /** iTunes Search and lrc.red's search, each on its own server, with the ids Spicy is then asked by. */
    private class Searches(client: OkHttpClient, clock: Clock) {
        val itunes = MockWebServer().apply { start() }
        val lrcRed = MockWebServer().apply { start() }
        val appleIds = AppleMusicIdResolver(client, "test", itunes.url("/").toString(), Dns.SYSTEM, clock)
        val isrcs = IsrcResolver(client, "test", IsrcLyricsHostBreaker(clock), lrcRed.url("/api/v1").toString(), lyricsDns = Dns.SYSTEM)

        fun itunesFinds(id: Long?) = itunes.enqueue(
            MockResponse().setBody(
                if (id == null) """{"results":[]}"""
                else """{"results":[{"trackId":$id,"trackName":"Song","artistName":"Artist","trackTimeMillis":200000}]}""",
            ),
        )

        fun lrcRedFinds(isrc: String) = lrcRed.enqueue(
            MockResponse().setBody(
                """{"results":[{"isrc":"$isrc","track_name":"Song","artist_name":"Artist","duration":200,"timing_type":"word"}]}""",
            ),
        )

        fun shutdown() {
            itunes.shutdown()
            lrcRed.shutdown()
        }
    }

    private fun spicyWith(s: Searches) = SpicyLyricsSource(
        client, "test", baseUrl = server.url("/spicy").toString(), clock = clock,
        isrcResolver = s.isrcs, appleMusicIds = s.appleIds, lyricsDns = Dns.SYSTEM,
    )

    @Test fun `a first lookup without ids takes the Apple Music id Apple's source just found, and searches nothing`() = runTest {
        val s = Searches(client, clock)
        try {
            s.itunesFinds(42)
            // Apple's source runs first in the walk and searches iTunes for the song.
            assertEquals("42", s.appleIds.find(query(spotifyTrackId = null)))
            serve(syllable)
            assertNotNull(spicyWith(s).resolve(query(spotifyTrackId = null)))
            assertEquals("/spicy/v1/lyrics/42", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("no second iTunes search", 1, s.itunes.requestCount)
            assertEquals("lrc.red never delays LRCLIB (#671)", 0, s.lrcRed.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test fun `a first lookup with no id found yet asks nobody`() = runTest {
        val s = Searches(client, clock)
        try {
            serve(syllable)
            assertNull(spicyWith(s).resolve(query(spotifyTrackId = null)))
            assertEquals(0, calls)
        } finally {
            s.shutdown()
        }
    }

    @Test fun `an upgrade pass searches iTunes for the Apple Music id`() = runTest {
        val s = Searches(client, clock)
        try {
            s.itunesFinds(42)
            serve(syllable)
            assertNotNull(spicyWith(s).resolve(query(spotifyTrackId = null, searchForIds = true)))
            assertEquals("/spicy/v1/lyrics/42", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("lrc.red isn't asked when iTunes found the song", 0, s.lrcRed.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test fun `an upgrade pass asks lrc red for an ISRC only when iTunes has no match`() = runTest {
        val s = Searches(client, clock)
        try {
            s.itunesFinds(null)
            s.lrcRedFinds("USAAA0000001")
            serve(syllable)
            assertNotNull(spicyWith(s).resolve(query(spotifyTrackId = null, searchForIds = true)))
            assertEquals("/spicy/v1/lyrics/USAAA0000001", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, s.itunes.requestCount)
            assertEquals(1, s.lrcRed.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test fun `an iTunes 429 in an upgrade pass is thrown, so the pass stops instead of moving past the song`() = runTest {
        val s = Searches(client, clock)
        try {
            s.itunes.enqueue(MockResponse().setResponseCode(429))
            val thrown = runCatching { spicyWith(s).resolve(query(spotifyTrackId = null, searchForIds = true)) }.exceptionOrNull()
            assertEquals(429, (thrown as AppleTtmlLyricsSource.HttpStatusException).code)
            assertEquals("Spicy itself wasn't asked", 0, server.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test fun `a time-out is a miss that pauses the source`() = runTest {
        assertPausesFor { timeOut = true }
    }

    @Test fun `a 200 that isn't JSON, a challenge page, pauses the source instead of being asked every time`() = runTest {
        assertPausesFor { serve("<!DOCTYPE html><html><title>Just a moment...</title></html>") }
    }

    /** [fail] makes the next request fail: nothing is asked until the window has passed. */
    private suspend fun assertPausesFor(rateLimited: Boolean = false, fail: () -> Unit) {
        val spicy = source()
        fail()
        if (rateLimited) {
            // A 429 is thrown, so an upgrade pass reports RATE_LIMITED instead of reading it as "no word sync".
            assertThrows(AppleTtmlLyricsSource.HttpStatusException::class.java) { runBlocking { spicy.resolve(query()) } }
        } else {
            assertNull(spicy.resolve(query()))
        }
        assertEquals(1, calls)

        timeOut = false
        serve(syllable)
        now += SpicyLyricsSource.SKIP_WINDOW_MS - 1
        // The request isn't sent and the source says so: a walk reads it as a miss, an upgrade pass as PAUSED.
        assertThrows("still inside the window", SpicyLyricsSource.SkippedException::class.java) {
            runBlocking { spicy.resolve(query()) }
        }
        assertEquals(1, calls)

        now += 1
        assertNotNull("asked again once the window has passed", spicy.resolve(query()))
        assertEquals(2, calls)
    }

    @Test fun `no Spotify id, no ISRC and no search to make: no request`() = runTest {
        serve(syllable)
        assertNull(source().resolve(query(spotifyTrackId = null)))
        assertEquals(0, calls)
    }

    @Test fun `with no key the public API is never called`() = runTest {
        assertNull(source(baseUrl = SpicyLyricsSource.DEFAULT_BASE_URL).resolve(query()))
        assertEquals(0, calls)
    }

    @Test fun `the proxy is asked by track id with Stash's User-Agent and no key`() = runTest {
        serve(syllable)
        source().resolve(query())
        val request = server.takeRequest()
        assertEquals("/spicy/v1/lyrics/$TRACK_ID", request.requestUrl!!.encodedPath)
        assertEquals("Stash/test (Android)", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))
    }

    private companion object {
        const val TRACK_ID = "4uLU6hMCjMI75M1A2tKUQC"
    }
}
