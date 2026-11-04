package com.stash.core.media.cast

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.WebSongs
import com.stash.core.media.queueTrackId
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which queue rows a browser can't play (Remote v2 §5), as the queue names them. The playback service decides by the library row;
 * the queue's own Track is rebuilt from the item and carries neither the download nor the ISRC, so it can't decide by itself.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserSongsTest {
    private fun item(mediaId: String, trackId: Long? = mediaId.toLongOrNull(), title: String = "Song $mediaId"): MediaItem {
        val meta = MediaMetadata.Builder().setTitle(title).setArtist("Radiohead")
        if (trackId != null) meta.setExtras(Bundle().apply { putLong(StashPlaybackService.EXTRA_TRACK_ID, trackId) })
        return MediaItem.Builder().setMediaId(mediaId).setUri("stash-resolve://track/$mediaId").setMediaMetadata(meta.build()).build()
    }

    private fun row(id: Long, downloaded: Boolean, youtubeId: String? = null) = Track(
        id = id,
        title = "Song $id",
        artist = "Radiohead",
        source = MusicSource.YOUTUBE,
        youtubeId = youtubeId,
        isDownloaded = downloaded,
        filePath = if (downloaded) "/data/user/0/com.stash.app/files/music/radiohead/song-$id.flac" else null,
    )

    @Test fun `a downloaded song with no ids is phone-only by its row, though the queue's own Track looks playable`() {
        val downloaded = item("990432")
        val songs = mapOf("990432" to BrowserSongs.songFor(downloaded, row(990432, downloaded = true)))

        assertThat(BrowserSongs.phoneOnlyIds(listOf(downloaded), songs)).containsExactly(990432L)
        // What the queue rebuilds from the item: not downloaded, no ids. By itself it would show this row as playable.
        assertThat(WebSongs.playsOnWeb(Track(id = 990432, title = "Song 990432", artist = "Radiohead", source = MusicSource.YOUTUBE))).isTrue()
    }

    @Test fun `streamed songs, songs not looked up yet and id-less previews are not listed`() {
        val streamed = item("2")
        val notYet = item("3")
        val preview = item("dQw4w9WgXcQ", trackId = null)
        val blank = item("5", title = "")
        val songs = mapOf(
            "2" to BrowserSongs.songFor(streamed, row(2, downloaded = false, youtubeId = "FxlYPR8MEvY")),
            "dQw4w9WgXcQ" to null, // no title or artist would be stepped over, but the queue can't name it
            "5" to BrowserSongs.songFor(blank, null),
        )

        // The blank one has no song at all: stepped over there, so dimmed here.
        assertThat(BrowserSongs.phoneOnlyIds(listOf(streamed, notYet, preview, blank), songs)).containsExactly(5L)
    }

    @Test fun `the queue's Track id is the item's track id, else its numeric media id, else none`() {
        assertThat(item("990432", trackId = 42).queueTrackId()).isEqualTo(42)
        assertThat(MediaItem.Builder().setMediaId("17").build().queueTrackId()).isEqualTo(17)
        assertThat(MediaItem.Builder().setMediaId("dQw4w9WgXcQ").build().queueTrackId()).isEqualTo(0)
    }
}
