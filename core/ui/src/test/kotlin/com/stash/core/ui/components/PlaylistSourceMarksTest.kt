package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.MusicSource.BOTH
import com.stash.core.model.MusicSource.SOUNDCLOUD
import com.stash.core.model.MusicSource.SPOTIFY
import com.stash.core.model.MusicSource.YOUTUBE
import com.stash.core.model.Track
import org.junit.Test

/**
 * Which service dots a playlist shows (owner 2026-11-02: Daily Discover showed "Spotify • YouTube",
 * and "it's not sourced from either of those places"). A list Stash makes shows none; a list from a
 * service keeps that service's.
 */
class PlaylistSourceMarksTest {

    private fun song(source: MusicSource, soundCloudId: String? = null) =
        Track(title = "t", artist = "a", source = source, soundCloudId = soundCloudId)

    @Test
    fun `a list Stash makes shows no dot on its card or its page`() {
        listOf(
            "stash_mix_1", // Daily Discover, Recommended by Last.fm, the listener's own mixes
            "custom_8f14e45f-ceea-4f5c-b5a1-2c0b0f0f0f0f", // a playlist made in the app
            "share:abc123", // a Community follow
            "stash_downloads_mix", // Your Downloads
            "custom_web_xyz", // a web or Your record import
            "custom_sync_9", // a playlist mirrored from Stash on the web
        ).forEach { id ->
            assertThat(playlistSourceMark(BOTH, id)).isNull()
            assertThat(playlistSourceMarks(BOTH, id, listOf(song(YOUTUBE), song(SPOTIFY)))).isEmpty()
        }
    }

    @Test
    fun `a synced Spotify, YouTube Music or SoundCloud list keeps its own dot`() {
        assertThat(playlistSourceMark(SPOTIFY, "37i9dQZF1E37aaaaaaaa")).isEqualTo(SPOTIFY)
        assertThat(playlistSourceMark(YOUTUBE, "RDTMAK5uy_aaaaaaaaa")).isEqualTo(YOUTUBE)
        assertThat(playlistSourceMark(SOUNDCLOUD, "soundcloud:playlist:123")).isEqualTo(SOUNDCLOUD)
        assertThat(playlistSourceMarks(SPOTIFY, "37i9dQZF1E37aaaaaaaa", emptyList())).containsExactly(SPOTIFY)
        // A synced saved album keeps its service too.
        assertThat(playlistSourceMark(SPOTIFY, "album:spotify:FakeAlb1xxxxxxxxxxxxxx")).isEqualTo(SPOTIFY)
        assertThat(playlistSourceMark(YOUTUBE, "MPREb_abc")).isEqualTo(YOUTUBE)
    }

    @Test
    fun `an album saved from a service names that service though it is kept as BOTH`() {
        // A pasted Spotify album link
        assertThat(playlistSourceMark(BOTH, "album:spotify:FakeAlb1xxxxxxxxxxxxxx")).isEqualTo(SPOTIFY)
        // A pasted YouTube Music album link, or that album page's Save: one key
        assertThat(playlistSourceMark(BOTH, "album:youtube:MPREb_abc")).isEqualTo(YOUTUBE)
        assertThat(playlistSourceMarks(BOTH, "album:youtube:MPREb_abc", emptyList())).containsExactly(YOUTUBE)
        // Qobuz has no dot of its own
        assertThat(playlistSourceMark(BOTH, "album:qobuz:0886446224536")).isNull()
        assertThat(playlistSourceMark(BOTH, "album:qobuz_playlist:123")).isNull()
    }

    @Test
    fun `a link import names its songs' services on its page, in order, once each`() {
        val songs = listOf(
            song(SPOTIFY),
            song(BOTH),
            song(SPOTIFY),
            song(YOUTUBE, soundCloudId = "sc1"),
        )
        assertThat(playlistSourceMarks(BOTH, "custom_link_spotify_abc", songs))
            .containsExactly(SPOTIFY, YOUTUBE, SOUNDCLOUD).inOrder()
        assertThat(playlistSourceMarks(BOTH, "custom_link_yt_PLabc", listOf(song(YOUTUBE)))).containsExactly(YOUTUBE)
        // Before its songs load, it names nothing (and the header drops the dot after the marks).
        assertThat(playlistSourceMarks(BOTH, "custom_link_songs", emptyList())).isEmpty()
    }

    @Test
    fun `a link import's card shows no dot, as Library's grid always has`() {
        assertThat(playlistSourceMark(BOTH, "custom_link_spotify_abc")).isNull()
        assertThat(playlistSourceMark(BOTH, "custom_link_soundcloud_playlist_1")).isNull()
        assertThat(playlistSourceMark(BOTH, "custom_link_file_123")).isNull()
    }
}
