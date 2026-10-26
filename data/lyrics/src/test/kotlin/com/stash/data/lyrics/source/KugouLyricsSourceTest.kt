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
 * from YumaPlayer / ArchiveTune (GPL-3.0); the miss-versus-failure rule and
 * the name check on songs and candidates are Stash's own (see
 * LrclibLyricsSource, IsrcResolver).
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

    /** A song-search hit: its hash, length in seconds, and KuGou's title and artist for it ([query]'s song by default). */
    private data class Hit(
        val hash: String,
        val duration: Int,
        val songname: String = "Off The Grid",
        val singer: String = "Kanye West",
    )

    private fun songSearch(vararg songs: Hit) = MockResponse().setBody(
        """{"status":1,"errcode":0,"error":"","data":{"info":[""" +
            songs.joinToString(",") {
                """{"hash":"${it.hash}","duration":${it.duration},"songname":"${it.songname}","singername":"${it.singer}"}"""
            } + "]}}",
    )

    /** One lyrics-search candidate as KuGou sends it: the file's id, its own title and artist, its origin. */
    private fun candidate(id: Long, song: String = "Off The Grid", singer: String = "Kanye West", from: String = "x") =
        """{"id":$id,"product_from":"$from","duration":279000,"accesskey":"KEY$id","song":"$song","singer":"$singer"}"""

    private fun candidateList(vararg candidates: String) = MockResponse().setBody(
        """{"status":200,"info":"","errcode":0,"errmsg":"","expire":0,"candidates":[""" + candidates.joinToString(",") + "]}",
    )

    private fun candidates(vararg ids: Long) = candidateList(*ids.map { candidate(it) }.toTypedArray())

    private fun download(content: String) = MockResponse().setBody("""{"content":"$content"}""")

    private fun query(durationMs: Long? = 279_000) = LyricsQuery(
        trackId = 1L, title = "Off The Grid (Live)", artist = "Kanye West, Playboi Carti & Fivio",
        album = "DONDA", albumArtist = null, durationMs = durationMs, youtubeVideoId = null,
    )

    /** Library row 18205, a mix stub: its 241 s came from Last.fm (a radio edit's length; the album cut is 326 s). */
    private fun shookOnes(durationMs: Long = 241_000) = LyricsQuery(
        trackId = 18205L, title = "Shook Ones, Pt. II", artist = "Mobb Deep",
        album = "Reminisce Hip Hop Classics", albumArtist = "Various", durationMs = durationMs, youtubeVideoId = null,
    )

    private fun asked(title: String, artist: String) = LyricsQuery(
        trackId = 1L, title = title, artist = artist, album = null, albumArtist = null, durationMs = null, youtubeVideoId = null,
    )

    @Test
    fun `synced lyrics come from the first duration-matched song's first candidate`() = runTest {
        server.enqueue(songSearch(Hit("H1", 279)))
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
        server.enqueue(songSearch(Hit("H1", 100)))       // 179 s off: skipped, no hash lookup
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
        server.enqueue(songSearch(Hit("H1", 279)))
        server.enqueue(MockResponse().setBody(
            """{"status":200,"info":"","errcode":0,"errmsg":"","expire":0,"candidates":[""" +
                """{"id":5,"product_from":"第三方歌词","duration":279000,"accesskey":"KEY5","song":"Off The Grid","singer":"Kanye West"},""" +
                """{"id":6,"product_from":"官方推荐歌词","duration":279000,"accesskey":"KEY6","song":"Off The Grid","singer":"Kanye West"}]}""",
        ))
        server.enqueue(download(lrc("[00:01.00]line")))

        val result = source.resolve(query())

        assertThat(result?.sourceLyricsId).isEqualTo("6")
        server.takeRequest(); server.takeRequest()
        assertThat(server.takeRequest().path).contains("id=6")
    }

    /**
     * Live KuGou, 2026-10-26: "Shook Ones, Pt. II" asked at Last.fm's 241 s. Within 8 s were only a
     * remix with no lyrics files and Mobb Deep's "The Infamous" (245 s), whose official file
     * 292220097 the length alone took: the row stored the wrong song's lyrics.
     */
    @Test
    fun `another song by the same artist is skipped whatever its length`() = runTest {
        server.enqueue(songSearch(
            Hit("ALBUM", 325, "Shook Ones, Pt. II (Explicit)", "Mobb Deep"),
            Hit("PIZZA", 289, "Shook Ones, Pt. II (Explicit)", "Mobb Deep"),
            Hit("REMIX", 237, "Shook Ones, Pt. II (NohaBeats Remix)", "Mobb Deep"),
            Hit("INFAMOUS", 245, "The Infamous", "Mobb Deep"),
        ))
        server.enqueue(candidateList())                   // the remix's hash: no files
        server.enqueue(candidateList())                   // keyword search

        assertThat(source.resolve(shookOnes())).isNull()
        server.takeRequest()
        assertThat(server.takeRequest().path).contains("hash=REMIX")
        val keywordSearch = server.takeRequest().path!!
        assertThat(keywordSearch).doesNotContain("hash=")  // "The Infamous" never asked for
        assertThat(keywordSearch).contains("duration=241000")
        assertThat(server.requestCount).isEqualTo(3)
    }

    /** A hash search returns other songs' files too (live: "The Infamous" also brought "Lifestyles of the Infamous"). */
    @Test
    fun `a file for another song is passed over, even an official one`() = runTest {
        server.enqueue(songSearch(Hit("ALBUM", 325, "Shook Ones, Pt. II (Explicit)", "Mobb Deep")))
        server.enqueue(candidateList(
            candidate(292220097L, "The Infamous", "Mobb Deep", from = "官方推荐歌词"),
            candidate(769439370L, "Shook Ones, Pt. II", "Mobb Deep", from = "第三方歌词"),
        ))
        server.enqueue(download(lrc("[00:06.89]Yeah, to all the killers and a hundred dollar billas")))

        val result = source.resolve(shookOnes(durationMs = 326_000))

        assertThat(result?.sourceLyricsId).isEqualTo("769439370")
        server.takeRequest(); server.takeRequest()
        assertThat(server.takeRequest().path).contains("id=769439370")
    }

    @Test
    fun `the keyword search's answer for another song is a miss`() = runTest {
        server.enqueue(songSearch())
        server.enqueue(candidateList(candidate(292220097L, "The Infamous", "Mobb Deep", from = "官方推荐歌词")))

        assertThat(source.resolve(shookOnes())).isNull()
        assertThat(server.requestCount).isEqualTo(2)      // nothing downloaded
    }

    @Test
    fun `a title with nothing to match on asks KuGou nothing`() = runTest {
        assertThat(source.resolve(asked(title = "(Intro)", artist = "Mobb Deep"))).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `KuGou's tags and aliases on the same title and artist still match`() {
        val shook = asked("Shook Ones, Pt. II", "Mobb Deep")
        assertThat(KugouLyricsSource.isSameSong(shook, "Shook Ones, Pt. II (Explicit)", "Mobb Deep")).isTrue()
        assertThat(KugouLyricsSource.isSameSong(shook, "Shook Ones, Pt. II (LP版)", "Mobb Deep")).isTrue()
        assertThat(KugouLyricsSource.isSameSong(asked("Dynamite", "BTS"), "Dynamite", "BTS(防弹少年团)")).isTrue()
        assertThat(KugouLyricsSource.isSameSong(query(), "Off The Grid", "Kanye West")).isTrue()
    }

    @Test
    fun `another title or another artist is another song`() {
        val shook = asked("Shook Ones, Pt. II", "Mobb Deep")
        assertThat(KugouLyricsSource.isSameSong(shook, "The Infamous", "Mobb Deep")).isFalse()
        assertThat(KugouLyricsSource.isSameSong(shook, "Shook Ones, Pt. II", "OMA")).isFalse()   // iTunes lists one by OMA
        assertThat(KugouLyricsSource.isSameSong(shook, "", "")).isFalse()
        // Live, 2026-10-26: files credited to Hua满楼 rank second for Jay Chou's 晴天.
        assertThat(KugouLyricsSource.isSameSong(asked("晴天", "Jay Chou"), "晴天", "Hua满楼")).isFalse()
    }

    /** Live, 2026-10-26: KuGou answers romanised asks in the original script, and lists 周杰伦, not Jay Chou. */
    @Test
    fun `a name in another script neither passes nor fails`() {
        assertThat(KugouLyricsSource.isSameSong(asked("晴天", "Jay Chou"), "晴天", "周杰伦")).isTrue()
        assertThat(KugouLyricsSource.isSameSong(asked("Lemon", "Kenshi Yonezu"), "Lemon", "米津玄師")).isTrue()
        assertThat(KugouLyricsSource.isSameSong(asked("Gangnam Style (강남스타일)", "PSY"), "강남스타일", "PSY")).isTrue()
        // Nothing left to compare: neither name can say it is the song.
        assertThat(KugouLyricsSource.isSameSong(asked("Qing Tian", "Jay Chou"), "晴天", "周杰伦")).isFalse()
    }
}

