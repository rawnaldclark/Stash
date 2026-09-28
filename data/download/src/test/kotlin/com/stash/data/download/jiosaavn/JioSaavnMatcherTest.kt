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
        // 6 s apart, so two recordings rather than copies, each 3 s from the request.
        val result = JioSaavnMatcher.best(
            TrackQuery("Artist", "Song", durationMs = 203_000L),
            listOf(
                song(id = "a", name = "Song", artist = "Artist", duration = 200, album = "Album A"),
                song(id = "b", name = "Song", artist = "Artist", duration = 206, album = "Different Record"),
            ),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `exact album breaks an otherwise exact recording tie`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Artist", "Song", album = "Album A", durationMs = 203_000L),
            listOf(
                song(id = "b", name = "Song", artist = "Artist", duration = 206, album = "Different Record"),
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
            liveSongs(AE_MERE_HUMSAFAR),
        )

        assertThat(result?.song?.id).isIn(AE_MERE_HUMSAFAR_COPIES)
    }

    @Test
    fun `live 484 - an unrelated compilation album still gets a copy`() {
        val result = JioSaavnMatcher.best(
            TrackQuery(ALKA_AND_UDIT, "Ae Mere Humsafar", album = "Bollywood Romance", durationMs = 351_000L),
            liveSongs(AE_MERE_HUMSAFAR),
        )

        assertThat(result?.song?.id).isIn(AE_MERE_HUMSAFAR_COPIES)
    }

    @Test
    fun `live 484 - a search download with no album still gets a copy`() {
        val result = JioSaavnMatcher.best(TrackQuery(ALKA_AND_UDIT, "Ae Mere Humsafar"), liveSongs(AE_MERE_HUMSAFAR))

        assertThat(result?.song?.id).isIn(AE_MERE_HUMSAFAR_COPIES)
    }

    // Pan-India dubs: one title, singer and length in every language. Only the
    // Telugu copies of "Fear Song" pass the artist gate (the Tamil ones credit
    // the lyricist too), so merging them would hand everyone the Telugu dub.
    @Test
    fun `live dub - a song whose title exists in several languages stays ambiguous`() {
        val result = JioSaavnMatcher.best(TrackQuery(ANIRUDH, "Fear Song", durationMs = 195_000L), liveSongs(FEAR_SONG))

        assertThat(result).isNull()
    }

    @Test
    fun `live dub - a Tamil album never gets the Telugu dub`() {
        val result = JioSaavnMatcher.best(
            TrackQuery(ANIRUDH, "Fear Song", album = "Devara Part 1 (Tamil)", durationMs = 195_000L),
            liveSongs(FEAR_SONG),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `a language the request names rejects other-language dubs`() {
        // Only the Telugu copies, so no other-language title is left to block the merge.
        val telugu = liveSongs(FEAR_SONG).filter { it.language == "telugu" }
        assertThat(telugu.map { it.id }).containsExactly("m0Yt29rq", "8_n_c7ay", "jqWYUCWU")

        val result = JioSaavnMatcher.best(
            TrackQuery(ANIRUDH, "Fear Song", album = "Devara Part 1 (Tamil)", durationMs = 195_000L),
            telugu,
        )

        assertThat(result).isNull()
    }

    @Test
    fun `live dub - Mehabooba without an album stays ambiguous`() {
        val result = JioSaavnMatcher.best(TrackQuery("Ananya Bhat", "Mehabooba", durationMs = 212_000L), liveSongs(MEHABOOBA))

        assertThat(result).isNull()
    }

    @Test
    fun `live dub - a Telugu album picks the Telugu dub`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Ananya Bhat", "Mehabooba", album = "KGF Chapter 2 - Telugu", durationMs = 212_000L),
            liveSongs(MEHABOOBA),
        )

        assertThat(result?.song?.id).isEqualTo("92hBBPo0")
    }

    @Test
    fun `live dub - a language named in any album picks that language`() {
        // Not the dub's own album name, so only the language gate can single out the Telugu copy.
        val result = JioSaavnMatcher.best(
            TrackQuery("Ananya Bhat", "Mehabooba", album = "Telugu Hits 2022", durationMs = 212_000L),
            liveSongs(MEHABOOBA),
        )

        assertThat(result?.song?.id).isEqualTo("92hBBPo0")
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
                song(
                    id = "d",
                    name = "Ae Mere Humsafar [From \"Baazigar\"]",
                    artist = "Vinod Rathod, Alka Yagnik",
                    duration = 452,
                    album = "Mushy Love Songs of Bollywood",
                ),
            ),
        )

        assertThat(result?.song?.id).isEqualTo("a")
    }

    @Test
    fun `a Form typo in the soundtrack credit is still a copy`() {
        // Live 2026-09-28: 'Jaadu Teri Nazar (Form "Darr")' is listed next to the Darr original.
        val result = JioSaavnMatcher.best(
            TrackQuery("Udit Narayan", "Jaadu Teri Nazar", durationMs = 280_000L),
            listOf(
                song(id = "a", name = "Jaadu Teri Nazar", artist = "Udit Narayan", duration = 280, album = "Darr"),
                song(
                    id = "b",
                    name = "Jaadu Teri Nazar (Form \"Darr\")",
                    artist = "Udit Narayan",
                    duration = 279,
                    album = "Hit Songs Of Yash Chopra Films",
                ),
            ),
        )

        assertThat(result?.song?.id).isEqualTo("a")
    }

    @Test
    fun `a tie with a featured-artist title stays ambiguous`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Artist", "Song", durationMs = 200_000L),
            listOf(
                song(id = "a", name = "Song", artist = "Artist", duration = 200, album = "Album A"),
                song(id = "b", name = "Song (feat. X)", artist = "Artist", duration = 200, album = "Album B"),
            ),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `a clean and an explicit copy stay ambiguous when the request cannot tell`() {
        // explicit = null, as search-tab downloads send it.
        val result = JioSaavnMatcher.best(
            TrackQuery("Artist", "Song", durationMs = 200_000L),
            listOf(
                song(id = "a", name = "Song", artist = "Artist", duration = 200, album = "Album A"),
                song(id = "b", name = "Song", artist = "Artist", duration = 200, album = "Album B", explicit = true),
            ),
        )

        assertThat(result).isNull()
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

    @Test
    fun `a duet request never takes a same-titled solo as a copy`() {
        // The artist check lets a solo by one of the duet's singers through, so without
        // comparing singers the solo counted as a copy and could win the tie.
        val query = TrackQuery(ALKA_AND_UDIT, "Ae Mere Humsafar", durationMs = 300_000L)
        val duet = song(id = "duet", name = "Ae Mere Humsafar", artists = listOf("Alka Yagnik", "Udit Narayan"), duration = 302, album = "Album A")
        val solo = song(id = "solo", name = "Ae Mere Humsafar", artists = listOf("Udit Narayan"), duration = 300, album = "Album B")

        assertThat(JioSaavnMatcher.best(query, listOf(duet, solo))?.song?.id).isNotEqualTo("solo")
        assertThat(JioSaavnMatcher.best(query, listOf(solo, duet))?.song?.id).isNotEqualTo("solo")
    }

    /** A saved live search response, parsed by the real [JioSaavnClient] as in JioSaavnClientTest. */
    private fun liveSongs(fixture: String): List<JioSaavnSong> = MockWebServer().use { server ->
        val body = javaClass.classLoader!!.getResource("fixtures/$fixture")!!.readText()
        server.enqueue(MockResponse().setBody(body))
        server.start()
        val client = JioSaavnClient(OkHttpClient()).apply { baseUrl = server.url("/").toString() }
        val outcome = runBlocking { client.search(fixture) }
        (outcome as JioSaavnSearchOutcome.Success).songs
    }

    private fun song(
        id: String = "rjkrTnma",
        name: String = "Kesariya",
        artist: String = "Arijit Singh",
        artists: List<String> = listOf(artist),
        duration: Int? = 268,
        mediaUrl: String = "https://aac.saavncdn.com/song_320.mp4",
        album: String = "Brahmastra",
        explicit: Boolean = false,
        language: String? = "hindi",
    ) = JioSaavnSong(
        id = id,
        name = name,
        duration = duration,
        explicitContent = explicit,
        album = JioSaavnAlbum(album),
        artists = JioSaavnArtists(artists.map { JioSaavnArtist(it) }),
        image = listOf(JioSaavnImage("500x500", "https://c.saavncdn.com/cover.jpg")),
        downloadUrl = listOf(JioSaavnMediaLink("320kbps", mediaUrl)),
        language = language,
    )

    private companion object {
        const val ALKA_AND_UDIT = "Alka Yagnik & Udit Narayan"
        const val ANIRUDH = "Anirudh Ravichander"
        const val AE_MERE_HUMSAFAR = "jiosaavn_search_ae_mere_humsafar.json"
        const val FEAR_SONG = "jiosaavn_search_fear_song.json"
        const val MEHABOOBA = "jiosaavn_search_mehabooba.json"

        /** Every copy of the 1988 recording in the fixture; its sixth result is a remix. */
        val AE_MERE_HUMSAFAR_COPIES = listOf("S1Yo84Ql", "4TFF_9qZ", "-EFMYYTR", "g2B_ionR", "boha3h8c")
    }
}
