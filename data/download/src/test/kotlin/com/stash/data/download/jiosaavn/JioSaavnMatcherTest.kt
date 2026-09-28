package com.stash.data.download.jiosaavn

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.TrackQuery
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class JioSaavnMatcherTest {
    @Test
    fun `exact artist title and duration selects 320kbps media`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Arijit Singh", "Kesariya", album = "Brahmastra", durationMs = 268_000L),
            listOf(song()),
        )
        assertThat(result).isNotNull()
        assertThat(result!!.media.quality).isEqualTo("320kbps")
        assertThat(result.media.url).endsWith("_320.mp4")
    }

    @Test
    fun `parenthetical decoration on the query title still matches the plain title`() {
        // Device-verified miss 2026-08-13: Spotify names the track
        // 'Apna Bana Le (From "Bhediya")', JioSaavn names it 'Apna Bana Le'.
        val result = JioSaavnMatcher.best(
            TrackQuery("Arijit Singh, Sachin-Jigar", "Apna Bana Le (From \"Bhediya\")", durationMs = 261_000L),
            listOf(song(name = "Apna Bana Le", artist = "Sachin-Jigar, Arijit Singh", duration = 261)),
        )
        assertThat(result).isNotNull()
    }

    @Test
    fun `karaoke candidate is rejected when target is the studio song`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Imagine Dragons", "Believer", durationMs = 204_000L),
            listOf(song(name = "Believer (Karaoke Version)", artist = "Imagine Dragons Karaoke Band", duration = 204)),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `version qualifier must agree in both directions`() {
        val query = TrackQuery("Pandera", "Terranova (Pandera Airplay Edit)", durationMs = 220_000L)
        assertThat(JioSaavnMatcher.best(query, listOf(song(name = "Terranova", artist = "Pandera", duration = 220)))).isNull()
        assertThat(
            JioSaavnMatcher.best(
                query,
                listOf(song(name = "Terranova (Pandera Airplay Edit)", artist = "Pandera", duration = 220)),
            ),
        ).isNotNull()
    }

    @Test
    fun `large duration mismatch is rejected even with exact text`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Radiohead", "Karma Police", durationMs = 261_000L),
            listOf(song(name = "Karma Police", artist = "Radiohead", duration = 410)),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `non-https 320 media is rejected`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Arijit Singh", "Kesariya", durationMs = 268_000L),
            listOf(song(mediaUrl = "http://aac.saavncdn.com/song_320.mp4")),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `equally exact recordings are rejected when album cannot disambiguate`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Artist", "Song", durationMs = 200_000L),
            listOf(
                song(id = "a", name = "Song", artist = "Artist", duration = 200, album = "Album A"),
                song(id = "b", name = "Song (Reprise)", artist = "Artist", duration = 200, album = "Different Record"),
            ),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `exact album breaks an otherwise exact recording tie`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Artist", "Song", album = "Album A", durationMs = 200_000L),
            listOf(
                song(id = "b", name = "Song (Reprise)", artist = "Artist", duration = 200, album = "Different Record"),
                song(id = "a", name = "Song", artist = "Artist", duration = 200, album = "Album A"),
            ),
        )

        assertThat(result?.song?.id).isEqualTo("a")
    }

    // #484: the live search returns one 1988 recording on several
    // compilations; each query must get a copy instead of "ambiguous".
    @Test
    fun `live 484 - a soundtrack album name that is not exact still gets a copy`() {
        val result = JioSaavnMatcher.best(
            TrackQuery(
                ALKA_AND_UDIT,
                "Ae Mere Humsafar",
                album = "Qayamat Se Qayamat Tak (Original Motion Picture Soundtrack)",
                durationMs = 353_000L,
            ),
            liveSongs(),
        )

        assertThat(result?.song?.id).isIn(AE_MERE_HUMSAFAR_COPIES)
    }

    @Test
    fun `live 484 - an unrelated compilation album still gets a copy`() {
        val result = JioSaavnMatcher.best(
            TrackQuery(ALKA_AND_UDIT, "Ae Mere Humsafar", album = "Bollywood Romance", durationMs = 351_000L),
            liveSongs(),
        )

        assertThat(result?.song?.id).isIn(AE_MERE_HUMSAFAR_COPIES)
    }

    @Test
    fun `live 484 - a search download with no album still gets a copy`() {
        val result = JioSaavnMatcher.best(TrackQuery(ALKA_AND_UDIT, "Ae Mere Humsafar"), liveSongs())

        assertThat(result?.song?.id).isIn(AE_MERE_HUMSAFAR_COPIES)
    }

    @Test
    fun `copies up to four seconds apart are one recording`() {
        // Live 2026-09-28: the Baazigar "Ae Mere Humsafar" is listed at 450, 453 and 454 s.
        val result = JioSaavnMatcher.best(
            TrackQuery(
                "Vinod Rathod, Alka Yagnik",
                "Ae Mere Humsafar",
                album = "Baazigar (Original Motion Picture Soundtrack)",
                durationMs = 450_000L,
            ),
            listOf(
                song(id = "a", name = "Ae Mere Humsafar", artist = "Vinod Rathod, Alka Yagnik", duration = 450, album = "Baazigar"),
                song(id = "b", name = "Ae Mere Humsafar", artist = "Vinod Rathod, Alka Yagnik", duration = 453, album = "Best of Shahrukh Khan"),
                song(
                    id = "c",
                    name = "Ae Mere Humsafar (From \"Baazigar\")",
                    artist = "Vinod Rathod, Alka Yagnik",
                    duration = 454,
                    album = "Bollywood Queens",
                ),
            ),
        )

        assertThat(result?.song?.id).isEqualTo("a")
    }

    @Test
    fun `a tie with a differently versioned title stays ambiguous`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Alka Yagnik", "Ae Mere Humsafar", durationMs = 352_000L),
            listOf(
                song(id = "a", name = "Ae Mere Humsafar", artist = "Alka Yagnik", duration = 352, album = "Album A"),
                song(id = "b", name = "Ae Mere Humsafar (Sad)", artist = "Alka Yagnik", duration = 352, album = "Album B"),
            ),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `same-title recordings six seconds apart stay ambiguous`() {
        // Both sit 3 s from the requested 355 s, well inside the duration tolerance, so they tie.
        val result = JioSaavnMatcher.best(
            TrackQuery("Alka Yagnik", "Ae Mere Humsafar", durationMs = 355_000L),
            listOf(
                song(id = "a", name = "Ae Mere Humsafar", artist = "Alka Yagnik", duration = 352, album = "Album A"),
                song(id = "b", name = "Ae Mere Humsafar", artist = "Alka Yagnik", duration = 358, album = "Album B"),
            ),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `a tie with an unknown duration stays ambiguous`() {
        val query = TrackQuery("Alka Yagnik", "Ae Mere Humsafar", durationMs = 352_000L)
        val known = song(id = "a", name = "Ae Mere Humsafar", artist = "Alka Yagnik", duration = 352, album = "Album A")
        val unknown = song(id = "b", name = "Ae Mere Humsafar", artist = "Alka Yagnik", duration = null, album = "Album B")

        assertThat(JioSaavnMatcher.best(query, listOf(known, unknown))).isNull()
        assertThat(JioSaavnMatcher.best(query, listOf(unknown, known))).isNull()
    }

    /** The #484 live response, parsed by the real [JioSaavnClient] as in JioSaavnClientTest. */
    private fun liveSongs(): List<JioSaavnSong> = MockWebServer().use { server ->
        val body = javaClass.classLoader!!.getResource("fixtures/jiosaavn_search_ae_mere_humsafar.json")!!.readText()
        server.enqueue(MockResponse().setBody(body))
        server.start()
        val client = JioSaavnClient(OkHttpClient()).apply { baseUrl = server.url("/").toString() }
        val outcome = runBlocking { client.search("$ALKA_AND_UDIT Ae Mere Humsafar") }
        (outcome as JioSaavnSearchOutcome.Success).songs
    }

    private fun song(
        id: String = "rjkrTnma",
        name: String = "Kesariya",
        artist: String = "Arijit Singh",
        duration: Int? = 268,
        mediaUrl: String = "https://aac.saavncdn.com/song_320.mp4",
        album: String = "Brahmastra",
    ) = JioSaavnSong(
        id = id,
        name = name,
        duration = duration,
        explicitContent = false,
        album = JioSaavnAlbum(album),
        artists = JioSaavnArtists(listOf(JioSaavnArtist(artist))),
        image = listOf(JioSaavnImage("500x500", "https://c.saavncdn.com/cover.jpg")),
        downloadUrl = listOf(JioSaavnMediaLink("320kbps", mediaUrl)),
    )

    private companion object {
        const val ALKA_AND_UDIT = "Alka Yagnik & Udit Narayan"

        /** Every copy of the 1988 recording in the fixture; its sixth result is a remix. */
        val AE_MERE_HUMSAFAR_COPIES = listOf("S1Yo84Ql", "4TFF_9qZ", "-EFMYYTR", "g2B_ionR", "boha3h8c")
    }
}
