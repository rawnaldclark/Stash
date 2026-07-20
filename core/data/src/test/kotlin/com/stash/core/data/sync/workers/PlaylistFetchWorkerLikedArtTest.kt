package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistFetchWorkerLikedArtTest {

    @Test
    fun `Spotify liked art uses first nonblank track image and upgrades it`() {
        val smallArt = "https://i.scdn.co/image/ab67616d00001e02abc"

        val result = likedPlaylistArtUrl(sequenceOf(null, "  ", smallArt, "later"))

        assertThat(result).isEqualTo("https://i.scdn.co/image/ab67616d0000b273abc")
    }

    @Test
    fun `YouTube liked art uses first nonblank track image and upgrades it`() {
        val smallArt = "https://lh3.googleusercontent.com/cover=w120-h120-l90-rj"

        val result = likedPlaylistArtUrl(sequenceOf("", smallArt, "later"))

        assertThat(result).isEqualTo(
            "https://lh3.googleusercontent.com/cover=w1024-h1024-l90-rj"
        )
    }

    @Test
    fun `liked art is absent when every track image is blank`() {
        assertThat(likedPlaylistArtUrl(sequenceOf(null, "", "\t"))).isNull()
    }
}
