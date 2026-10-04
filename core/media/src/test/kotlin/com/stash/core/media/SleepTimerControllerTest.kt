package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    private val playerState = MutableStateFlow(PlayerState(currentTrack = track(1)))
    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { this@mockk.playerState } returns this@SleepTimerControllerTest.playerState
        every { playbackSpeed } returns MutableStateFlow(1f)
        coEvery { pause() } returns Unit
    }

    @Test
    fun `countdown pauses playback when it elapses`() = runTest {
        val timer = SleepTimerController(playerRepository, backgroundScope)

        timer.startMinutes(15)
        assertThat(timer.state.value).isInstanceOf(SleepTimerController.State.Countdown::class.java)

        advanceTimeBy(14 * 60_000L)
        runCurrent()
        coVerify(exactly = 0) { playerRepository.pause() }

        advanceTimeBy(60_001L)
        runCurrent()
        coVerify(exactly = 1) { playerRepository.pause() }
        assertThat(timer.state.value).isEqualTo(SleepTimerController.State.Off)
    }

    @Test
    fun `re-arming replaces the running countdown`() = runTest {
        val timer = SleepTimerController(playerRepository, backgroundScope)

        timer.startMinutes(15)
        advanceTimeBy(10 * 60_000L)
        timer.startMinutes(30) // re-arm: the old 15m deadline must not fire

        advanceTimeBy(20 * 60_000L)
        runCurrent()
        coVerify(exactly = 0) { playerRepository.pause() }

        advanceTimeBy(10 * 60_001L)
        runCurrent()
        coVerify(exactly = 1) { playerRepository.pause() }
    }

    @Test
    fun `cancel disarms without pausing`() = runTest {
        val timer = SleepTimerController(playerRepository, backgroundScope)

        timer.startMinutes(15)
        timer.cancel()
        assertThat(timer.state.value).isEqualTo(SleepTimerController.State.Off)

        advanceTimeBy(60 * 60_000L)
        runCurrent()
        coVerify(exactly = 0) { playerRepository.pause() }
    }

    @Test
    fun `end of track pauses when the current track nears its end`() = runTest {
        val position = MutableStateFlow(0L)
        every { playerRepository.currentPosition } returns position
        playerState.value = PlayerState(currentTrack = track(1), durationMs = 100_000L)

        val timer = SleepTimerController(playerRepository, backgroundScope)
        timer.stopAtEndOfTrack(fadeOutMs = 0L) // no fade → pause the instant we hit the window
        runCurrent()
        coVerify(exactly = 0) { playerRepository.pause() }

        // Mid-track: nowhere near the end → no pause.
        position.value = 50_000L
        runCurrent()
        coVerify(exactly = 0) { playerRepository.pause() }

        // At the end of the track → pause and disarm.
        position.value = 100_000L
        runCurrent()
        coVerify(exactly = 1) { playerRepository.pause() }
        assertThat(timer.state.value).isEqualTo(SleepTimerController.State.Off)
    }

    @Test
    fun `end of track disarms without pausing when the user skips to another track`() = runTest {
        val position = MutableStateFlow(0L)
        every { playerRepository.currentPosition } returns position
        playerState.value = PlayerState(currentTrack = track(1), durationMs = 100_000L)

        val timer = SleepTimerController(playerRepository, backgroundScope)
        timer.stopAtEndOfTrack()
        runCurrent()

        // User skips to a different track before it ended → disarm, don't pause.
        playerState.value = PlayerState(currentTrack = track(2), durationMs = 100_000L)
        position.value = 1L // nudge a re-collect so the track-change check runs
        runCurrent()

        coVerify(exactly = 0) { playerRepository.pause() }
        assertThat(timer.state.value).isEqualTo(SleepTimerController.State.Off)
    }

    @Test
    fun `end of track at 2x pauses before the next song starts`() = runTest {
        val position = MutableStateFlow(0L)
        every { playerRepository.currentPosition } returns position
        every { playerRepository.playbackSpeed } returns MutableStateFlow(2f)
        playerState.value = PlayerState(currentTrack = track(1), durationMs = 100_000L)

        val timer = SleepTimerController(playerRepository, backgroundScope)
        timer.stopAtEndOfTrack(fadeOutMs = 10_000L)
        runCurrent()

        // 16 s of song left is 8 s at 2x: already inside the 10 s fade.
        position.value = 84_000L
        runCurrent()

        // The song runs out 8 s from now, so the pause must land by then (not 16 s from now,
        // by which point the next song has been playing for 8 s).
        advanceTimeBy(8_000L)
        runCurrent()
        coVerify(exactly = 1) { playerRepository.pause() }
        assertThat(timer.state.value).isEqualTo(SleepTimerController.State.Off)
    }

    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A")
}
