package com.stash.feature.listening

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listening.ListeningHistoryPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.feature.listening.FakeHistory.Companion.TODAY
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test

/** Home's "Your listening": hidden until the first listen, then Recently played and the History door. */
@OptIn(ExperimentalCoroutinesApi::class)
class YourListeningViewModelTest {

    private val paused = MutableStateFlow(false)
    private val preference = mockk<ListeningHistoryPreference>().also { every { it.paused } returns paused }
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>().also { every { it.isConnected() } returns true }
    /** Noon on [TODAY], UTC. */
    private val now = MutableStateFlow(TODAY * 86_400_000L + 12 * 3_600_000L)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(fake: FakeHistory): YourListeningViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return YourListeningViewModel(fake.repository(), preference, player, connectivity, now).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }
    }

    @Test fun `no listens yet, the section stays hidden`() = runTest {
        val vm = viewModel(FakeHistory())
        advanceUntilIdle()
        assertThat(vm.state.value).isNull()
    }

    @Test fun `the first listen shows the section`() = runTest {
        val fake = FakeHistory()
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value).isNull()
        fake.add(fake.track(1), TODAY, 60)
        advanceUntilIdle()
        assertThat(vm.state.value?.recent?.map { it.id }).containsExactly(1L)
    }

    @Test fun `recently played lists songs once, newest first, with today's count`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1), TODAY - 1, 600)
        fake.add(fake.track(2), TODAY, 60)
        fake.add(fake.track(1), TODAY, 90)
        val vm = viewModel(fake)
        advanceUntilIdle()
        val s = vm.state.value!!
        assertThat(s.recent.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(s.todayPlays).isEqualTo(2)
        assertThat(historySubtitle(s)).isEqualTo("2 plays today")
    }

    @Test fun `nothing today, the door says when the last song was heard`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1), TODAY - 1, 600)
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(historySubtitle(vm.state.value!!)).isEqualTo("Last played yesterday")
    }

    @Test fun `paused, the door says so`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1), TODAY, 60)
        paused.value = true
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(historySubtitle(vm.state.value!!)).isEqualTo("Paused")
    }

    @Test fun `tapping a recent song plays it with the rail queued`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1), TODAY, 60)
        fake.add(fake.track(2), TODAY, 70)
        fake.add(fake.track(3), TODAY, 80)
        val vm = viewModel(fake)
        advanceUntilIdle()
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        coEvery { player.setQueue(capture(queue), capture(start), any()) } returns Unit
        vm.playRecent(vm.state.value!!.recent[1]); advanceUntilIdle()
        assertThat(queue.captured.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(start.captured).isEqualTo(1)
        coVerify { player.setQueue(any(), any(), PlaybackSource.Listening(YourListeningViewModel.RECENT_LABEL)) }
    }

    @Test fun `offline, a recent song that isn't downloaded says so`() = runTest {
        every { connectivity.isConnected() } returns false
        val fake = FakeHistory()
        fake.add(fake.track(1), TODAY, 60)
        val vm = viewModel(fake)
        advanceUntilIdle()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        runCurrent()
        vm.playRecent(vm.state.value!!.recent[0]); advanceUntilIdle()
        assertThat(messages).containsExactly(SONG_NEEDS_CONNECTION)
        coVerify(exactly = 0) { player.setQueue(any(), any(), any()) }
    }
}
