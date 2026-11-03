package com.stash.core.data.weblink

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.scannedTrackId
import org.junit.Test

/**
 * The one rule (spec 2026-11-03 §5) the handoff, the mirror and a browser playing this phone's queue share: which songs the web
 * can play, and the Song it gets. Before it, the handoff dropped SoundCloud silently while the mirror sent it phone-only.
 */
class WebSongsTest {
    private val spotifyId = "3xKsf9qdS1CyvXSMEid6g8"
    private val youtubeId = "uzS3WG6__G4"

    private fun track(
        source: MusicSource = MusicSource.SPOTIFY,
        id: Long = 42L,
        title: String = "Pink + White",
        isrc: String? = null,
        spotifyUri: String? = null,
        youtubeId: String? = null,
        downloaded: Boolean = false,
    ) = Track(
        id = id, title = title, artist = "Frank Ocean", album = "Blonde", durationMs = 184_516, source = source, isrc = isrc,
        spotifyUri = spotifyUri, youtubeId = youtubeId, isDownloaded = downloaded,
        filePath = if (downloaded) "/storage/music/a.flac" else null,
    )

    @Test fun `a SoundCloud song stays on the phone, with no id a web source could mistake for another upload`() {
        val t = track(MusicSource.SOUNDCLOUD, isrc = "USUM71607009", spotifyUri = "spotify:track:$spotifyId", youtubeId = youtubeId)
        val s = WebSongs.songOf(t)!!
        assertThat(s.phoneOnly).isTrue()
        assertThat(s.youtubeId).isNull()
        assertThat(s.spotifyId).isNull()
        assertThat(WebSongs.playsOnWeb(t)).isFalse()
    }

    @Test fun `a cloud song stays on the phone, ids or not`() {
        val t = track(MusicSource.CLOUD, isrc = "USUM71607009")
        assertThat(WebSongs.songOf(t)!!.phoneOnly).isTrue()
        assertThat(WebSongs.playsOnWeb(t)).isFalse()
    }

    @Test fun `an All Songs scan stays on the phone`() {
        val t = track(MusicSource.LOCAL, id = scannedTrackId(7L), isrc = "USUM71607009")
        assertThat(WebSongs.songOf(t)!!.phoneOnly).isTrue()
        assertThat(WebSongs.playsOnWeb(t)).isFalse()
    }

    @Test fun `a local file without an ISRC, Spotify or YouTube id stays on the phone; with one it plays on the web`() {
        assertThat(WebSongs.playsOnWeb(track(MusicSource.LOCAL))).isFalse()
        assertThat(WebSongs.playsOnWeb(track(MusicSource.LOCAL, isrc = "USUM71607009"))).isTrue()
    }

    @Test fun `a download with ids plays on the web (it streams there, the file never leaves); one without stays`() {
        val withIds = track(spotifyUri = "spotify:track:$spotifyId", youtubeId = youtubeId, downloaded = true)
        val s = WebSongs.songOf(withIds)!!
        assertThat(s.phoneOnly).isFalse()
        assertThat(s.spotifyId).isEqualTo(spotifyId)
        assertThat(s.youtubeId).isEqualTo(youtubeId)
        assertThat(WebSongs.playsOnWeb(track(downloaded = true))).isFalse()
    }

    @Test fun `a streamed song with no ids plays on the web, found by its words`() {
        val t = track()
        assertThat(WebSongs.songOf(t)!!.phoneOnly).isFalse()
        assertThat(WebSongs.playsOnWeb(t)).isTrue()
    }

    @Test fun `a song with no title is no song`() {
        assertThat(WebSongs.songOf(track(title = " "))).isNull()
        assertThat(WebSongs.songOf(track(MusicSource.SOUNDCLOUD, title = ""))).isNull()
        assertThat(WebSongs.playsOnWeb(track(title = ""))).isFalse()
    }
}
