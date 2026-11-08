package com.stash.data.lyrics.source

import com.stash.core.common.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** Id fallback, syllable -> TTML, credits, and the skip window. Robolectric for real org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpicyLyricsSourceTest {

    private val spotifyId = "4uLU6hMCjMI75M1A2tKUQC"
    private val syllables = """[{"Type":"Vocal","OppositeAligned":false,"Lead":{"StartTime":1.0,"EndTime":3.0,"Syllables":[
        {"Text":"Hel","StartTime":1.0,"EndTime":1.4,"IsPartOfWord":true},
        {"Text":"lo","StartTime":1.4,"EndTime":2.0,"IsPartOfWord":false},
        {"Text":"world","StartTime":2.0,"EndTime":3.0,"IsPartOfWord":false}]}}]"""

    private fun json(type: String, content: String, source: String = "spicy_lyrics") =
        """{"Status":200,"Type":"$type","Body":{"id":"$spotifyId","source":"$source","Type":"$type",""" +
            """"UploadAttribution":{"Uploader":{"username":"up","url":"https://spicylyrics.org/u/up"},""" +
            """"Maker":{"username":"mk","url":"https://spicylyrics.org/u/mk"}},"Content":$content}}"""

    private lateinit var server: MockWebServer
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private var status = 200
    private var body = ""
    private var now = 0L
    private lateinit var source: SpicyLyricsSource

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return MockResponse().setResponseCode(status).setBody(body)
            }
        }
        server.start()
        source = SpicyLyricsSource(
            OkHttpClient(), "test",
            baseUrl = server.url("/").toString(),
            clock = object : Clock { override fun now() = now },
            lyricsDns = Dns.SYSTEM,
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun query(spotify: String? = spotifyId, isrc: String? = null) = LyricsQuery(
        trackId = 1L, title = "Song", artist = "Artist", album = null, albumArtist = null,
        durationMs = 200_000L, youtubeVideoId = null, isrc = isrc, interactive = true, spotifyTrackId = spotify,
    )

    @Test fun `a syllable sync becomes word-synced TTML with its credits`() = runTest {
        body = json("Syllable", syllables)
        val r = source.resolve(query())!!
        assertNotNull(r.ttml)
        assertEquals("Hello world", r.plainText)
        assertEquals("[00:01.00]Hello world", r.syncedLrc)
        assertEquals("up", r.credits!!.uploaderName)
        assertEquals("https://spicylyrics.org/u/mk", r.credits!!.makerUrl)
        assertEquals("/v1/lyrics/$spotifyId", paths.single())
    }

    @Test fun `credits come only from Spicy's own catalogue`() = runTest {
        body = json("Syllable", syllables, source = "lrc_red")
        assertNull(source.resolve(query())!!.credits)
    }

    @Test fun `without a Spotify id the track's ISRC is asked, normalised`() = runTest {
        body = json("Syllable", syllables)
        source.resolve(query(spotify = null, isrc = "us-aaa-00-00001"))
        assertEquals("/v1/lyrics/USAAA0000001", paths.single())
    }

    @Test fun `with no id to ask by, nothing is requested`() = runTest {
        assertNull(source.resolve(query(spotify = null, isrc = null)))
        assertTrue(paths.isEmpty())
    }

    @Test fun `an untimed sync is a miss, so the next source gets its turn`() = runTest {
        body = json("Static", """[{"Text":"words"}]""")
        assertNull(source.resolve(query()))
    }

    @Test fun `a 404 is a miss and does not stop the source`() = runTest {
        status = 404
        body = """{"Status":404}"""
        assertNull(source.resolve(query()))
        assertNull(source.resolve(query()))
        assertEquals(2, paths.size)
    }

    @Test fun `a 429 stops the source for ten minutes, then it asks again`() = runTest {
        status = 429
        assertThrows(AppleTtmlLyricsSource.HttpStatusException::class.java) { runBlocking { source.resolve(query()) } }
        assertThrows(SpicyLyricsSource.SkippedException::class.java) { runBlocking { source.resolve(query()) } }
        assertEquals(1, paths.size)
        now += SpicyLyricsSource.SKIP_WINDOW_MS
        status = 404
        assertNull(source.resolve(query()))
        assertEquals(2, paths.size)
    }
}