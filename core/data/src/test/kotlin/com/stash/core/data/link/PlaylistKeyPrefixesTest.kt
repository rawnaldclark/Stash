package com.stash.core.data.link

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.album.SavedAlbums
import com.stash.core.data.playlistfile.PlaylistFileImport
import org.junit.Test

/**
 * The keys core:ui's `PlaylistSourceMarks.kt` reads to tell a list that comes from a service from
 * one Stash made (no Spotify / YouTube dots on Stash's own lists, owner 2026-11-02). core:ui can't
 * see core:data, so it keeps its own copies of these prefixes (`LINK_IMPORT_PREFIX`,
 * `SPOTIFY_ALBUM_PREFIX`, `YOUTUBE_ALBUM_PREFIX`), and its `PlaylistSourceMarksTest` pins those
 * copies to the same strings. If a key changes here, this fails: change the copies with it.
 */
class PlaylistKeyPrefixesTest {

    @Test fun `every link import's key starts custom_link_`() {
        assertThat(LinkImporter.PLAYLIST_PREFIX).isEqualTo("custom_link_")
        assertThat(LinkImporter.SONGS_SOURCE_ID).startsWith("custom_link_")
        assertThat(LinkImporter.youTubePlaylistSourceId("VLPLabc")).isEqualTo("custom_link_yt_PLabc")
        assertThat(PlaylistFileImport.SOURCE_PREFIX).isEqualTo("custom_link_file_")
    }

    @Test fun `a saved Spotify or YouTube Music album's key starts album_spotify_ or album_youtube_`() {
        assertThat(SavedAlbums.sourceId("spotify", "4m2880jivSbbyEGAKfITCa")).isEqualTo("album:spotify:4m2880jivSbbyEGAKfITCa")
        assertThat(SavedAlbums.sourceId("youtube", "MPREb_abc")).isEqualTo("album:youtube:MPREb_abc")
    }
}
