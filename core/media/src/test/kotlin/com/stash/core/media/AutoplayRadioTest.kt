package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import org.junit.Test

/**
 * Autoplay radio (user ask, 2026-09-07): "search a song, play it, it ends,
 * silence." With the setting on, the moment the LAST queued track starts a
 * song radio seeded from it is spliced behind it. These pin when that fires.
 */
class AutoplayRadioTest {
    private fun track(id: Long) = Track(id = id, title = "t$id", artist = "a", youtubeId = "v$id", isStreamable = true)
    private fun state(queue: Int, index: Int, repeat: RepeatMode = RepeatMode.OFF, playing: Boolean = true) = PlayerState(
        currentTrack = track(index + 1L), queue = (1..queue).map { track(it.toLong()) }, currentIndex = index, repeatMode = repeat,
        isPlaying = playing,
    )

    @Test fun `a one-off play arms the radio the moment it starts`() {
        assertThat(shouldAutoplayRadio(enabled = true, radioActive = false, libraryShuffleActive = false, state = state(queue = 1, index = 0), triedTrackId = null)).isTrue()
    }

    @Test fun `the last track of an album arms it too, an earlier one does not`() {
        assertThat(shouldAutoplayRadio(true, false, false, state(queue = 10, index = 9), null)).isTrue()
        assertThat(shouldAutoplayRadio(true, false, false, state(queue = 10, index = 3), null)).isFalse()
    }

    @Test fun `off by default means never`() {
        assertThat(shouldAutoplayRadio(false, false, false, state(queue = 1, index = 0), null)).isFalse()
    }

    @Test fun `repeat, a running station, or library shuffle already keep the music going`() {
        assertThat(shouldAutoplayRadio(true, false, false, state(queue = 1, index = 0, repeat = RepeatMode.ALL), null)).isFalse()
        assertThat(shouldAutoplayRadio(true, false, false, state(queue = 1, index = 0, repeat = RepeatMode.ONE), null)).isFalse()
        assertThat(shouldAutoplayRadio(true, radioActive = true, libraryShuffleActive = false, state = state(queue = 1, index = 0), triedTrackId = null)).isFalse()
        assertThat(shouldAutoplayRadio(true, radioActive = false, libraryShuffleActive = true, state = state(queue = 1, index = 0), triedTrackId = null)).isFalse()
    }

    @Test fun `a track that was already tried is not retried on every tick`() {
        assertThat(shouldAutoplayRadio(true, false, false, state(queue = 1, index = 0), triedTrackId = 1L)).isFalse()
    }

    /** Pixel 6, 2026-09-07: the cold-start restore (last session, paused) armed a station before anyone pressed play. */
    @Test fun `a paused session does not arm, pressing play does`() {
        assertThat(shouldAutoplayRadio(true, false, false, state(queue = 1, index = 0, playing = false), null)).isFalse()
        assertThat(shouldAutoplayRadio(true, false, false, state(queue = 1, index = 0, playing = true), null)).isTrue()
    }

    @Test fun `an empty queue arms nothing`() {
        assertThat(shouldAutoplayRadio(true, false, false, PlayerState(), null)).isFalse()
    }
}
