package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.download.lyrics.LyricsFetchStatus
import com.stash.data.download.lyrics.LyricsUpgradeTrigger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The "Re-check library for word-synced lyrics" row: what it shows, and what a tap / Cancel does. */
class LyricsSettingsRecheckTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val workStatus = MutableStateFlow<LyricsFetchStatus>(LyricsFetchStatus.Idle)
    private val finishedAt = MutableStateFlow<Long?>(null)
    private val cursor = MutableStateFlow(0L)

    private val trigger = mockk<LyricsUpgradeTrigger>(relaxUnitFun = true) {
        every { recheckStatus } returns workStatus
    }
    private val preference = mockk<LyricsPreference> {
        every { sourcePreference } returns flowOf(LyricsSourcePreference.APPLE_MUSIC)
        every { recheckFinishedAt } returns finishedAt
        every { recheckCursor } returns cursor
    }

    private fun viewModel() = LyricsSettingsViewModel(preference, mockk(), trigger)

    @Test fun `tapping start enqueues the one re-check job, cancel stops it`() {
        val vm = viewModel()

        vm.startRecheck()
        vm.cancelRecheck()

        verify(exactly = 1) { trigger.enqueueLibraryRecheck() }
        verify(exactly = 1) { trigger.cancelLibraryRecheck() }
        // Nothing else: the row never starts Library Health's run.
        verify(exactly = 0) { trigger.enqueueManualFetch() }
    }

    @Test fun `the row follows the job from idle to waiting to checking to last checked`() = runTest {
        val vm = viewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.recheckState.collect {} }

        assertThat(vm.recheckState.value).isEqualTo(LyricsRecheckUiState.Idle())

        workStatus.value = LyricsFetchStatus.Queued
        assertThat(vm.recheckState.value).isEqualTo(LyricsRecheckUiState.Waiting)

        workStatus.value = LyricsFetchStatus.Running(done = 1_240, total = 8_312)
        cursor.value = 7_000L
        assertThat(vm.recheckState.value).isEqualTo(LyricsRecheckUiState.Checking(1_240, 8_312))

        workStatus.value = LyricsFetchStatus.Done(upgraded = 3, fetched = 0, notFound = 0, failed = 0, bailed = false)
        cursor.value = 0L
        finishedAt.value = 1_700_000_000_000L
        assertThat(vm.recheckState.value).isEqualTo(LyricsRecheckUiState.Idle(lastCheckedAtMs = 1_700_000_000_000L))

        job.cancel()
    }

    @Test fun `a sweep the services cut short says so`() {
        val bailed = LyricsFetchStatus.Done(upgraded = 0, fetched = 0, notFound = 0, failed = 5, bailed = true)
        assertThat(lyricsRecheckUiState(bailed, finishedAtMs = null, cursor = 42L))
            .isEqualTo(LyricsRecheckUiState.Idle(stoppedEarly = true))
    }

    @Test fun `a cancelled sweep offers to carry on`() {
        // Cancel leaves no Done behind (the work is CANCELLED, read as Idle), only the cursor.
        assertThat(lyricsRecheckUiState(LyricsFetchStatus.Idle, finishedAtMs = 5L, cursor = 42L))
            .isEqualTo(LyricsRecheckUiState.Idle(lastCheckedAtMs = 5L, partway = true))
    }
}
