package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareLinksTest {

    @Test
    fun `spotify uri converts to open-spotify link`() {
        assertThat(spotifyShareUrl("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
            .isEqualTo("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
    }

    @Test
    fun `full spotify url passes through`() {
        val url = "https://open.spotify.com/track/abc123"
        assertThat(spotifyShareUrl(url)).isEqualTo(url)
    }

    @Test
    fun `unknown or blank spotify identity yields null`() {
        assertThat(spotifyShareUrl(null)).isNull()
        assertThat(spotifyShareUrl("")).isNull()
        assertThat(spotifyShareUrl("spotify:album:xyz")).isNull()
    }

    private val track = Track(
        title = "Song & Dance", artist = "Aphex Twin", album = "Drukqs", durationMs = 125_000, isrc = "GBBPW0100025",
        spotifyUri = "spotify:track:abc", youtubeId = "xyz", albumArtUrl = "https://i.scdn.co/image/a",
    )
    /** What the sheet sends for [track]. */
    private val song = SharedTrack("Song & Dance", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "abc", "xyz",
        artUrl = "https://i.scdn.co/image/a")
    private val longLink = ShareLinks.trackUrl(song)

    @Test
    fun `stash link is the short link when the server makes one in time`() = runTest {
        var sent: SharedTrack? = null
        val link = stashSongLink(track, create = { sent = it; "https://stashfm.app/t/Ab3xY9qk" })
        assertThat(link).isEqualTo("https://stashfm.app/t/Ab3xY9qk")
        assertThat(sent).isEqualTo(song) // album, length, ISRC, ids and cover all go to the server
    }

    @Test
    fun `the song link sends the cover only from a known art host, never a local file`() = runTest {
        val sent = mutableListOf<SharedTrack>()
        val create: suspend (SharedTrack) -> String? = { sent += it; null }
        stashSongLink(track.copy(albumArtUrl = "file:///data/user/0/com.stash.app/files/art/7.jpg"), create)
        stashSongLink(track.copy(albumArtUrl = "/storage/emulated/0/Music/cover.jpg"), create)
        stashSongLink(track.copy(albumArtUrl = "content://media/external/audio/albumart/3"), create)
        stashSongLink(track.copy(albumArtUrl = "https://tracker.example/pixel.jpg"), create)
        stashSongLink(track, create)
        assertThat(sent.map { it.artUrl }).containsExactly(null, null, null, null, "https://i.scdn.co/image/a").inOrder()
    }

    @Test
    fun `a slow server falls back to the long link after the time box`() = runTest {
        val link = stashSongLink(track, create = { awaitCancellation() })
        assertThat(link).isEqualTo(longLink)
        assertThat(currentTime).isEqualTo(ShareLinks.SHORT_LINK_TIMEOUT_MS)
        assertThat(ShareLinks.SHORT_LINK_TIMEOUT_MS).isEqualTo(4_000L)
    }

    @Test
    fun `a refusal, a crash or no creator falls back to the long link`() = runTest {
        assertThat(stashSongLink(track, create = { null })).isEqualTo(longLink)
        assertThat(stashSongLink(track, create = { throw java.io.IOException("offline") })).isEqualTo(longLink)
        assertThat(stashSongLink(track, create = null)).isEqualTo(longLink)
    }

    @Test
    fun `the long link carries the song's details on the stashfm host, never the cover`() {
        assertThat(longLink).isEqualTo(
            "https://stashfm.app/t?t=Song+%26+Dance&a=Aphex+Twin&al=Drukqs&d=125000&isrc=GBBPW0100025&sp=abc&yt=xyz",
        )
    }

    @Test
    fun `youtube id builds a music watch link`() {
        assertThat(youtubeShareUrl("dQw4w9WgXcQ"))
            .isEqualTo("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        assertThat(youtubeShareUrl(null)).isNull()
        assertThat(youtubeShareUrl(" ")).isNull()
    }
}
