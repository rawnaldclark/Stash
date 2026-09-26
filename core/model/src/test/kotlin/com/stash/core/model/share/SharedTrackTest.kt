package com.stash.core.model.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import org.junit.Test

class SharedTrackTest {
    private val full = Track(
        title = "Avril 14th", artist = "Aphex Twin", album = "Drukqs", durationMs = 125_000,
        isrc = "GBBPW0100025", spotifyUri = "spotify:track:5Y6nVaayzitvsD5F7nr3DV", youtubeId = "d1fQx6aXhkc",
        source = MusicSource.SPOTIFY,
    )

    @Test fun `a track maps to a descriptor with every known field`() {
        assertThat(full.toSharedTrack()).isEqualTo(
            SharedTrack("Avril 14th", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "5Y6nVaayzitvsD5F7nr3DV", "d1fQx6aXhkc"),
        )
    }

    @Test fun `blank and zero fields are omitted and an open-spotify url yields its id`() {
        val t = Track(title = "T", artist = "A", album = "", durationMs = 0, isrc = " ",
            spotifyUri = "https://open.spotify.com/track/abc123?si=x", youtubeId = "")
        assertThat(t.toSharedTrack()).isEqualTo(SharedTrack("T", "A", spotifyId = "abc123"))
    }

    @Test fun `a descriptor becomes a stream-only BOTH track`() {
        val t = SharedTrack("Avril 14th", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "5Y6nVaayzitvsD5F7nr3DV", "d1fQx6aXhkc").toTrack()
        assertThat(t.source).isEqualTo(MusicSource.BOTH)
        assertThat(t.spotifyUri).isEqualTo("spotify:track:5Y6nVaayzitvsD5F7nr3DV")
        assertThat(t.youtubeId).isEqualTo("d1fQx6aXhkc")
        assertThat(t.isrc).isEqualTo("GBBPW0100025")
        assertThat(t.album).isEqualTo("Drukqs")
        assertThat(t.durationMs).isEqualTo(125_000)
        assertThat(t.isStreamable).isTrue()
        assertThat(t.id).isEqualTo(0L)
    }

    @Test fun `blank ids in a descriptor become null, never an empty unique key`() {
        val t = SharedTrack("T", "A", isrc = " ", spotifyId = "", youtubeId = "").toTrack()
        assertThat(t.isrc).isNull()
        assertThat(t.spotifyUri).isNull()
        assertThat(t.youtubeId).isNull()
    }
}
