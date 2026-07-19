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
    fun `end of track pauses on the next track transition only`() = runTest {
        val timer = SleepTimerController(playerRepository, backgroundScope)

        timer.stopAtEndOfTrack()
        runCurrent()
        coVerify(exactly = 0) { playerRepository.pause() }

        // Same track re-emission (pause/resume churn) must not trigger it.
        playerState.value = PlayerState(currentTrack = track(1), isPlaying = false)
        runCurrent()
        coVerify(exactly = 0) { playerRepository.pause() }

        playerState.value = PlayerState(currentTrack = track(2))
        runCurrent()
        coVerify(exactly = 1) { playerRepository.pause() }
        assertThat(timer.state.value).isEqualTo(SleepTimerController.State.Off)
    }

    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A")
}
