package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

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

    @Test
    fun `stash link is the https track link with the known ids`() {
        val link = stashShareLink(title = "Song & Dance", artist = "Aphex Twin", spotifyUri = "spotify:track:abc", youtubeId = "xyz")
        assertThat(link).startsWith("https://stashfm.app/t?t=Song+%26+Dance&a=Aphex+Twin")
        assertThat(link).contains("&sp=abc")
        assertThat(link).contains("&yt=xyz")
    }

    @Test
    fun `stash link omits missing ids`() {
        assertThat(stashShareLink("T", "A", spotifyUri = null, youtubeId = null))
            .isEqualTo("https://stashfm.app/t?t=T&a=A")
    }

    @Test
    fun `youtube id builds a music watch link`() {
        assertThat(youtubeShareUrl("dQw4w9WgXcQ"))
            .isEqualTo("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        assertThat(youtubeShareUrl(null)).isNull()
        assertThat(youtubeShareUrl(" ")).isNull()
    }
}
