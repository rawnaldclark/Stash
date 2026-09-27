package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import org.junit.Test

/** [withinLimits]: the Worker's field limits (worker src/validate.js), which mix links and Community posts both rely on. */
class SharedTrackLimitsTest {
    @Test fun `withinLimits clips text and drops over-long ids and art`() {
        val t = SharedTrack(title = "x".repeat(600), artist = "a", isrc = "I".repeat(21), spotifyId = "s".repeat(41),
            youtubeId = "y".repeat(21), artUrl = "https://i.scdn.co/" + "a".repeat(1000)).withinLimits()
        assertThat(t.title).hasLength(500)
        assertThat(listOf(t.isrc, t.spotifyId, t.youtubeId, t.artUrl)).containsExactly(null, null, null, null)
    }

    @Test fun `values exactly at the limit are kept`() {
        val t = SharedTrack(title = "x".repeat(500), artist = "a".repeat(500), album = "b".repeat(500), isrc = "I".repeat(20),
            spotifyId = "s".repeat(40), youtubeId = "y".repeat(20), artUrl = "https://i.scdn.co/".padEnd(1000, 'a'))
        assertThat(t.artUrl).hasLength(1000)
        assertThat(t.withinLimits()).isEqualTo(t)
    }

    @Test fun `cut never ends on half an emoji, and cut(0) is empty`() {
        assertThat("".cut(0)).isEqualTo("")
        assertThat(("n".repeat(9) + "🎧").cut(10)).isEqualTo("n".repeat(9))
        assertThat(("n".repeat(8) + "🎧").cut(10)).isEqualTo("n".repeat(8) + "🎧")
    }

    @Test fun `withinLimits never cuts an emoji in half`() {
        val text = "x".repeat(499) + "🎧"
        val t = SharedTrack(title = text, artist = text, album = text).withinLimits()
        assertThat(listOf(t.title, t.artist, t.album)).containsExactly("x".repeat(499), "x".repeat(499), "x".repeat(499))
    }
}
