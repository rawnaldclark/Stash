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
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return YourListeningViewModel(fake.repository(), preference, player, connectivity, now, dispatcher).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }
    }

    @Test fun `Home asks for genres through the once-a-day gate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var asks = 0
        val gate = OncePerDayGate()
        val fake = FakeHistory()
        repeat(2) {
            YourListeningViewModel(fake.repository(), preference, player, connectivity, now, dispatcher, { asks++ }, gate)
        }
        assertThat(asks).isEqualTo(1) // the second Home of the day doesn't ask again
        val tomorrow = FakeHistory(today = TODAY + 1)
        YourListeningViewModel(tomorrow.repository(), preference, player, connectivity, now, dispatcher, { asks++ }, gate)
        assertThat(asks).isEqualTo(2)
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

    @Test fun `the week card sums the last 7 days with a bar per day and the streak`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1), TODAY, 60, listenedMs = 120_000)
        fake.add(fake.track(2), TODAY - 1, 60)
        fake.add(fake.track(3), TODAY - 2, 60)
        fake.add(fake.track(4), TODAY - 9, 60) // before the week: no bar, still not in the streak
        val vm = viewModel(fake)
        advanceUntilIdle()
        val week = vm.state.value!!.week
        assertThat(week.plays).isEqualTo(3)
        assertThat(week.listenedMs).isEqualTo(120_000L + 2 * 200_000L)
        assertThat(week.estimated).isTrue()
        assertThat(week.bars).containsExactly(0L, 0L, 0L, 0L, 200_000L, 200_000L, 120_000L).inOrder()
        assertThat(week.streak).isEqualTo(3)
        assertThat(weekLine(week)).isEqualTo("3 plays · 3-day streak")
    }

    @Test fun `the streak counts back to the first listen, however long, like Your listening`() = runTest {
        val fake = FakeHistory().apply { fill(days = 450, perDay = 1) }
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value!!.week.streak).isEqualTo(450)
    }

    @Test fun `recently played shows library duplicates of one song once`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1, title = "Garden", artist = "Death Plus"), TODAY, 60)
        fake.add(fake.track(2, title = "garden", artist = "Death Plus"), TODAY, 70)
        fake.add(fake.track(3, title = "Other"), TODAY, 80)
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value!!.recent.map { it.id }).containsExactly(3L, 2L).inOrder()
    }

    @Test fun `the Top songs door counts library duplicates as one song`() = runTest {
        val fake = FakeHistory()
        repeat(2) { fake.add(fake.track(1, title = "Garden", artist = "Death Plus"), TODAY, 60 + it) }
        repeat(2) { fake.add(fake.track(2, title = "Garden", artist = "Death Plus"), TODAY, 70 + it) }
        repeat(3) { fake.add(fake.track(3, title = "Other"), TODAY, 80 + it) }
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value!!.topSongTitle).isEqualTo("Garden")
    }

    @Test fun `week line without a streak or listens`() {
        assertThat(weekLine(WeekSummary(60_000, 1, false, List(7) { 0L }, 1))).isEqualTo("1 play")
        assertThat(weekLine(WeekSummary.EMPTY)).isEqualTo("Nothing in the last 7 days")
    }

    @Test fun `the Top songs door names the 30-day number one`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1, title = "Taro"), TODAY, 60)
        fake.add(fake.track(1, title = "Taro"), TODAY - 3, 60)
        fake.add(fake.track(2, title = "Other"), TODAY, 70)
        fake.add(fake.track(3, title = "Ancient"), TODAY - 40, 70)
        fake.add(fake.track(3, title = "Ancient"), TODAY - 41, 70)
        fake.add(fake.track(3, title = "Ancient"), TODAY - 42, 70)
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value!!.topSongTitle).isEqualTo("Taro")
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
