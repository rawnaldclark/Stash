package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins [positionTicker] — the shared playback-position stream.
 *
 * The battery contract is the point of these tests: the ticker polls the
 * player ONLY while it is playing. Position cannot advance on its own while
 * paused, so a paused ticker must cost nothing — the old implementation
 * kept a `while (true) { delay(1s) }` loop running for the whole process
 * lifetime, and `distinctUntilChanged` hid it from every consumer because
 * the repeated positions were identical. That is why these tests count
 * POLLS rather than emissions: emissions looked fine while the CPU was
 * being woken 86,400 times a day with nothing playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionTickerTest {

    @Test
    fun `paused playback never polls the player`() = runTest {
        var polls = 0
        val state = MutableStateFlow(PlayerState(isPlaying = false, positionMs = 5_000L))
        val ticker = positionTicker(state, pollIntervalMs = 250L) { polls++; 5_000L }

        backgroundScope.launch { ticker.collect { } }
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()

        assertThat(polls).isEqualTo(0)
    }

    @Test
    fun `playing polls once per interval`() = runTest {
        var polls = 0
        val state = MutableStateFlow(PlayerState(isPlaying = true, positionMs = 0L))
        val ticker = positionTicker(state, pollIntervalMs = 250L) { polls++; polls * 250L }

        backgroundScope.launch { ticker.collect { } }
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        // t=0, 250, 500, 750 (t=1000 lands on the boundary and may not have
        // resumed yet) — the contract is "roughly 4/second", not a fencepost.
        assertThat(polls).isIn(4..5)
    }

    @Test
    fun `pausing stops the polling`() = runTest {
        var polls = 0
        val state = MutableStateFlow(PlayerState(isPlaying = true, positionMs = 0L))
        val ticker = positionTicker(state, pollIntervalMs = 250L) { polls++; polls * 250L }

        backgroundScope.launch { ticker.collect { } }
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        val whilePlaying = polls

        state.value = PlayerState(isPlaying = false, positionMs = 1_000L)
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()

        assertThat(polls).isEqualTo(whilePlaying)
    }

    @Test
    fun `seek while paused reports the new position`() = runTest {
        val state = MutableStateFlow(PlayerState(isPlaying = false, positionMs = 5_000L))
        val seen = mutableListOf<Long>()
        val ticker = positionTicker(state, pollIntervalMs = 250L) { 5_000L }

        backgroundScope.launch { ticker.collect { seen += it } }
        runCurrent()

        // A seek while paused arrives as a player-state update (the
        // onPositionDiscontinuity callback), not as a poll result.
        state.value = PlayerState(isPlaying = false, positionMs = 42_000L)
        runCurrent()

        assertThat(seen).containsExactly(5_000L, 42_000L).inOrder()
    }

    @Test
    fun `resuming after a pause polls again`() = runTest {
        var polls = 0
        val state = MutableStateFlow(PlayerState(isPlaying = false, positionMs = 0L))
        val ticker = positionTicker(state, pollIntervalMs = 250L) { polls++; 1_000L }

        backgroundScope.launch { ticker.collect { } }
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertThat(polls).isEqualTo(0)

        state.value = PlayerState(isPlaying = true, positionMs = 0L)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(polls).isAtLeast(4)
    }

    @Test
    fun `the shared ticker runs only while collected and never hands a late collector a stale position`() = runTest {
        var polls = 0
        var livePosition = 200_000L
        val state = MutableStateFlow(PlayerState(isPlaying = true, positionMs = 0L))
        val shared = positionTicker(state, pollIntervalMs = 250L) { polls++; livePosition }
            .shareAsPositionTicker(backgroundScope)

        // Someone watches (Now Playing open), then leaves: the ticker stops with them.
        val watcher = backgroundScope.launch { shared.collect { } }
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        watcher.cancel()
        runCurrent()
        val pollsWhileWatched = polls
        advanceTimeBy(60_000L)
        runCurrent()
        assertThat(polls).isEqualTo(pollsWhileWatched) // song still playing, nobody collecting: no polls

        // Minutes later the song has restarted (a loop, "previous"): a new collector (repeat-one just turned on) must
        // start from where the song is now, not from the 200 s it last heard, or 200 s -> 3 s reads as a loop.
        livePosition = 3_000L
        val firstSeen = mutableListOf<Long>()
        backgroundScope.launch { shared.collect { firstSeen += it } }
        runCurrent()

        assertThat(firstSeen.first()).isEqualTo(3_000L)
    }
}
