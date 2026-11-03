package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The 30 s that let you vote on a Community post (contract 2026-11-03): only playing time of its songs, played from it. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityListenTrackerTest {
    private val player = MutableStateFlow(PlayerState())
    private val heard = mutableListOf<String>()
    private val fromPost = PlaybackSource.Community(POST, "sad boy hours")

    private fun TestScope.tracker() =
        CommunityListenTracker(player, onHeard = { heard += it }, scope = backgroundScope, nowMs = { testScheduler.currentTime })

    /** The player now shows [song] (playing or not) from [source]; [positionMs] is where it is in the song. */
    private fun TestScope.show(song: Long?, playing: Boolean = true, source: PlaybackSource = fromPost, positionMs: Long = 0) {
        player.value = player.value.copy(
            currentTrack = song?.let { Track(id = it, title = "Song $it", artist = "A") },
            isPlaying = playing,
            source = source,
            positionMs = positionMs,
        )
        runCurrent()
    }

    private fun TestScope.play(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun `30 s of the post's songs playing marks it heard, once`() = runTest {
        tracker().begin(POST, listOf(1L, 2L)); runCurrent()
        show(1)
        play(29_999)
        assertThat(heard).isEmpty()
        play(1)
        assertThat(heard).containsExactly(POST)
        play(120_000)
        assertThat(heard).containsExactly(POST)
    }

    @Test fun `a pause or buffering stops the count, and play carries on from where it was`() = runTest {
        tracker().begin(POST, listOf(1L)); runCurrent()
        show(1)
        play(20_000)
        show(1, playing = false) // paused, or buffering: isPlaying is false either way
        play(600_000)
        assertThat(heard).isEmpty()
        show(1)
        play(9_999)
        assertThat(heard).isEmpty()
        play(1)
        assertThat(heard).containsExactly(POST)
    }

    @Test fun `a seek to the end adds nothing: it's playing time that counts`() = runTest {
        tracker().begin(POST, listOf(1L)); runCurrent()
        show(1)
        play(5_000)
        show(1, positionMs = 235_000)
        play(24_000)
        assertThat(heard).isEmpty()
        play(1_000)
        assertThat(heard).containsExactly(POST)
    }

    @Test fun `a skip to another of the post's songs keeps counting; a song that isn't the post's doesn't count`() = runTest {
        tracker().begin(POST, listOf(1L, 2L)); runCurrent()
        show(1)
        play(10_000)
        show(2) // skipped to the post's next song
        play(10_000)
        show(99) // a song added to the queue after the post's
        play(60_000)
        assertThat(heard).isEmpty()
        show(1)
        play(10_000)
        assertThat(heard).containsExactly(POST)
    }

    @Test fun `the same song played from elsewhere doesn't count`() = runTest {
        tracker().begin(POST, listOf(1L)); runCurrent()
        // The post's queue hasn't reached the player yet: the old one, playing the same song, still shows.
        show(1, source = PlaybackSource.Playlist(7, "Gym"))
        play(60_000)
        assertThat(heard).isEmpty()
        show(1)
        play(10_000)
        // Then the song is started from a playlist: that's playback from elsewhere, and the count ends.
        show(1, source = PlaybackSource.Playlist(7, "Gym"))
        play(60_000)
        show(1, source = PlaybackSource.Unknown)
        play(60_000)
        assertThat(heard).isEmpty()
    }

    @Test fun `a song post's radio doesn't count, but going back to the song does`() = runTest {
        tracker().begin(POST, listOf(1L)); runCurrent()
        show(1)
        play(10_000)
        // Play on a song post grows a radio around the song, which keeps playing.
        val radio = PlaybackSource.Radio("garden")
        show(1, source = radio)
        play(10_000)
        show(50, source = radio)
        play(120_000)
        assertThat(heard).isEmpty()
        show(1, source = radio) // back to the song
        play(10_000)
        assertThat(heard).containsExactly(POST)
    }

    @Test fun `a song shown paused never counts`() = runTest {
        tracker().begin(POST, listOf(1L)); runCurrent()
        show(1, playing = false)
        play(120_000)
        assertThat(heard).isEmpty()
    }

    @Test fun `played again later, the post carries on from where it was`() = runTest {
        val tracker = tracker()
        tracker.begin(POST, listOf(1L)); runCurrent()
        show(1)
        play(20_000)
        show(8, source = PlaybackSource.Library) // something else
        play(60_000)
        tracker.begin(POST, listOf(1L))
        show(1)
        play(10_000)
        assertThat(heard).containsExactly(POST)
    }

    @Test fun `another post's Play moves the count to that post`() = runTest {
        val tracker = tracker()
        tracker.begin(POST, listOf(1L)); runCurrent()
        show(1)
        play(20_000)
        tracker.begin(OTHER, listOf(2L))
        show(2, source = PlaybackSource.Community(OTHER, "late night"))
        play(30_000)
        assertThat(heard).containsExactly(OTHER)
    }

    private companion object {
        const val POST = "AAAAAAAA"
        const val OTHER = "BBBBBBBB"
    }
}
