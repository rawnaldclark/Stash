package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.SharedTrack
import org.junit.Test

class CommunityRowsTest {
    private fun post(kind: String = "playlist") = CommunityPost(
        id = "AAAAAAAA", kind = kind, title = "sad boy hours", name = "Maya", count = 42,
        artist = "Death Plus".takeIf { kind == "song" }, createdAt = 1, up = 18, down = 0,
    )

    @Test fun `each kind has its own word`() {
        assertThat(kindLabel("playlist")).isEqualTo(R.string.community_kind_playlist)
        assertThat(kindLabel("mix")).isEqualTo(R.string.community_kind_mix)
        assertThat(kindLabel("song")).isEqualTo(R.string.community_kind_song)
        // Anything the Worker adds later reads as a playlist rather than nothing.
        assertThat(kindLabel("album")).isEqualTo(R.string.community_kind_playlist)
    }

    @Test fun `a song's cover is its art, a playlist's its covers`() {
        assertThat(post(kind = "song").copy(art = "https://i.ytimg.com/a").coverUrls()).containsExactly("https://i.ytimg.com/a")
        assertThat(post().copy(covers = listOf("https://i.scdn.co/1", "https://i.scdn.co/2")).coverUrls()).hasSize(2)
    }

    @Test fun `a playlist's length is its songs' whole minutes, and unknown lengths count as nothing`() {
        fun lasting(vararg ms: Long?) = post().copy(tracks = ms.map { SharedTrack("T", "A", durationMs = it) })
        assertThat(lasting(3_600_000, 300_000).lengthMinutes()).isEqualTo(65)
        assertThat(lasting(59_000).lengthMinutes()).isEqualTo(0)
        assertThat(lasting(null, null).lengthMinutes()).isEqualTo(0)
    }
}
