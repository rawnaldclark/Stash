package com.stash.data.download.jiosaavn

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.TrackQuery
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
                song(id = "b", name = "Song", artist = "Artist", duration = 200, album = "Different Record"),
            ),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `exact album breaks an otherwise exact recording tie`() {
        val result = JioSaavnMatcher.best(
            TrackQuery("Artist", "Song", album = "Album A", durationMs = 200_000L),
            listOf(
                song(id = "b", name = "Song", artist = "Artist", duration = 200, album = "Different Record"),
                song(id = "a", name = "Song", artist = "Artist", duration = 200, album = "Album A"),
            ),
        )

        assertThat(result?.song?.id).isEqualTo("a")
    }

    private fun song(
        id: String = "rjkrTnma",
        name: String = "Kesariya",
        artist: String = "Arijit Singh",
        duration: Int = 268,
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
}
