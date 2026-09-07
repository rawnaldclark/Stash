package com.stash.data.lyrics.source

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * KuGou is the largest synced-lyrics catalogue reachable without a key:
 * search songs by "title - artist", take the ones within a few seconds of
 * the track's duration, ask for their lyrics candidates by hash, download
 * the first as base64 LRC; fall back to a keyword lyrics search. Ported
 * from YumaPlayer / ArchiveTune (GPL-3.0); the miss-versus-failure rule is
 * Stash's own (see LrclibLyricsSource).
 */
class KugouLyricsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: KugouLyricsSource

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        source = KugouLyricsSource(OkHttpClient(), searchBaseUrl = base, lyricsBaseUrl = base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun lrc(vararg lines: String) = Base64.getEncoder().encodeToString(lines.joinToString("\n").toByteArray())

    private fun songSearch(vararg songs: Pair<String, Int>) = MockResponse().setBody(
        """{"status":1,"errcode":0,"error":"","data":{"info":[""" +
            songs.joinToString(",") { (hash, dur) -> """{"hash":"$hash","duration":$dur}""" } + "]}}",
    )

    private fun candidates(vararg ids: Long) = MockResponse().setBody(
        """{"status":200,"info":"","errcode":0,"errmsg":"","expire":0,"candidates":[""" +
            ids.joinToString(",") { """{"id":$it,"product_from":"x","duration":279000,"accesskey":"KEY$it"}""" } + "]}",
    )

    private fun download(content: String) = MockResponse().setBody("""{"content":"$content"}""")

    private fun query(durationMs: Long? = 279_000) = LyricsQuery(
        trackId = 1L, title = "Off The Grid (Live)", artist = "Kanye West, Playboi Carti & Fivio",
        album = "DONDA", albumArtist = null, durationMs = durationMs, youtubeVideoId = null,
    )

    @Test
    fun `synced lyrics come from the first duration-matched song's first candidate`() = runTest {
        server.enqueue(songSearch("H1" to 279))
        server.enqueue(candidates(7L))
        server.enqueue(download(lrc("[ti:Off The Grid]", "[00:01.00]I been off the grid", "[00:05.20]Second line")))

        val result = source.resolve(query())

        assertThat(result?.sourceId).isEqualTo("kugou")
        assertThat(result?.syncedLrc).isEqualTo("[00:01.00]I been off the grid\n[00:05.20]Second line")
        assertThat(result?.plainText).isEqualTo("I been off the grid\nSecond line")
        assertThat(result?.sourceLyricsId).isEqualTo("7")
        assertThat(result?.instrumental).isFalse()
        val search = server.takeRequest().path!!
        assertThat(search).startsWith("/api/v3/search/song?")
        assertThat(search).contains("keyword=Off%20The%20Grid%20-%20Kanye%20West%E3%80%81Playboi%20Carti%E3%80%81Fivio")
        assertThat(server.takeRequest().path).contains("hash=H1")
        val dl = server.takeRequest().path!!
        assertThat(dl).startsWith("/download?")
        assertThat(dl).contains("id=7")
        assertThat(dl).contains("accesskey=KEY7")
    }

    @Test
    fun `a song outside the duration tolerance is skipped and the keyword search answers`() = runTest {
        server.enqueue(songSearch("H1" to 100))          // 179 s off: skipped, no hash lookup
        server.enqueue(candidates(9L))                    // keyword search
        server.enqueue(download(lrc("[00:01.00]line")))

        val result = source.resolve(query())

        assertThat(result?.sourceLyricsId).isEqualTo("9")
        server.takeRequest()
        val keywordSearch = server.takeRequest().path!!
        assertThat(keywordSearch).startsWith("/search?")
        assertThat(keywordSearch).contains("duration=279000")
        assertThat(keywordSearch).doesNotContain("hash=")
    }

    @Test
    fun `no candidates anywhere is a definitive miss`() = runTest {
        server.enqueue(songSearch())
        server.enqueue(candidates())

        assertThat(source.resolve(query())).isNull()
    }

    @Test
    fun `a server error is a failure, not a miss`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.resolve(query()) } }
    }

    @Test
    fun `the keyword strips parentheticals and joins artists the KuGou way`() {
        val keyword = KugouLyricsSource.keywordFor("Off The Grid (Live) [Remaster]", "Kanye West, Playboi Carti & Fivio Foreign")

        assertThat(keyword.title).isEqualTo("Off The Grid")
        assertThat(keyword.artist).isEqualTo("Kanye West、Playboi Carti、Fivio Foreign")
    }

    @Test
    fun `headers and credit lines are cut, only timed lines survive`() {
        val raw = listOf(
            "[ti:Off The Grid]", "[ar:Kanye West]", "[by:someone]",
            "[00:00.00]作词 : Kanye West",
            "[00:00.50]作曲：Kanye West",
            "[00:01.00]I been off the grid",
            "[00:05.20]Second line",
            "[03:59.00]Lyrics by : KuGou",
        ).joinToString("\n")

        assertThat(KugouLyricsSource.normalizeLrc(raw)).isEqualTo("[00:01.00]I been off the grid\n[00:05.20]Second line")
    }

    /**
     * ultrareview, 2026-09-07: the credit regex took ANY colon in a lyric for a credit
     * marker, and the head/tail scan then cut everything up to that line — "She said:
     * welcome home" in the first thirty lines silently dropped a song's whole opening.
     */
    @Test
    fun `a lyric line with a colon is not a credit line`() {
        val lines = (1..40).map { i -> "[%02d:%02d.00]Line %d".format(i / 6, (i % 6) * 10, i) }.toMutableList()
        lines[0] = "[00:00.00]作词 : Kanye West"
        lines[15] = "[02:30.00]She said: welcome home"
        lines[36] = "[06:00.00]Chorus: sing it back"
        lines[39] = "[06:30.00]混音 : KuGou"

        val out = KugouLyricsSource.normalizeLrc(lines.joinToString("\n")).lines()

        assertThat(out.first()).isEqualTo(lines[1])
        assertThat(out).contains("[02:30.00]She said: welcome home")
        assertThat(out).contains("[06:00.00]Chorus: sing it back")
        assertThat(out.last()).isEqualTo(lines[38])
    }

    /** Live KuGou (2026-09-06, Reckoner): candidate 1 was "第三方歌词" (third-party), candidate 2 "官方推荐歌词" (official). */
    @Test
    fun `an official recommendation outranks a third-party candidate`() = runTest {
        server.enqueue(songSearch("H1" to 279))
        server.enqueue(MockResponse().setBody(
            """{"status":200,"info":"","errcode":0,"errmsg":"","expire":0,"candidates":[""" +
                """{"id":5,"product_from":"第三方歌词","duration":279000,"accesskey":"KEY5"},""" +
                """{"id":6,"product_from":"官方推荐歌词","duration":279000,"accesskey":"KEY6"}]}""",
        ))
        server.enqueue(download(lrc("[00:01.00]line")))

        val result = source.resolve(query())

        assertThat(result?.sourceLyricsId).isEqualTo("6")
        server.takeRequest(); server.takeRequest()
        assertThat(server.takeRequest().path).contains("id=6")
    }
}

