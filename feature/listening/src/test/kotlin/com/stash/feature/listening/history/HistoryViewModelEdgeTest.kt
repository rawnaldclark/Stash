package com.stash.feature.listening.history

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listening.ListeningHistoryPreference
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.feature.listening.FakeHistory
import com.stash.feature.listening.FakeHistory.Companion.TODAY
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import java.time.ZoneOffset

/**
 * History's harder cases (PR #620 review): reloads racing each other, a failed page, a search that
 * has no match on or before a jumped-to day, a deep list read again in place, saved search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelEdgeTest {

    private val paused = MutableStateFlow(false)
    private val preference = mockk<ListeningHistoryPreference>(relaxed = true).also { every { it.paused } returns paused }
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>().also { every { it.isConnected() } returns true }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(history: ListeningHistoryRepository, handle: SavedStateHandle = SavedStateHandle()): HistoryViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HistoryViewModel(
            handle, history, preference, player,
            mockk(relaxed = true) { every { getUserCreatedPlaylists() } returns flowOf(emptyList()) },
            connectivity, { ZoneOffset.UTC },
        )
    }

    @Test fun `a jump made while a sync is in flight finishes loading and pages on`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()

        // A listen lands: the sync job pages newer, held mid-read.
        fake.newerGate = CompletableDeferred()
        fake.add(fake.track(7), TODAY, minute = 1_000)
        advanceUntilIdle()
        // The user jumps meanwhile; its read is held too.
        fake.pageGate = CompletableDeferred()
        vm.jumpTo(TODAY - 12)
        runCurrent()
        assertThat(vm.state.value.loading).isTrue()

        fake.newerGate!!.complete(Unit)
        advanceUntilIdle()
        fake.pageGate!!.complete(Unit)
        advanceUntilIdle()

        val s = vm.state.value
        assertThat(s.loading).isFalse()
        assertThat(s.days.first().day).isEqualTo(TODAY - 12)
        assertThat(s.hasOlder).isTrue()
        // Paging still works.
        val calls = fake.pageCalls.size
        vm.loadOlder(); advanceUntilIdle()
        assertThat(fake.pageCalls.size).isGreaterThan(calls)
    }

    @Test fun `a failed older page shows Retry, and Retry loads it`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        fake.failNextPage = true
        vm.loadOlder(); advanceUntilIdle()
        assertThat(vm.state.value.olderFailed).isTrue()
        assertThat(vm.state.value.days.sumOf { it.rows.size }).isEqualTo(200)

        vm.retryOlder(); advanceUntilIdle()
        assertThat(vm.state.value.olderFailed).isFalse()
        assertThat(vm.state.value.days.sumOf { it.rows.size }).isEqualTo(400)
    }

    @Test fun `jumping with a search to a day older than every match lands on the nearest match after it`() = runTest {
        val fake = FakeHistory()
        // 300 matches over the last 30 days; plus one listen, not a match, 40 days ago.
        for (d in 29 downTo 0) for (i in 0 until 10) fake.add(fake.track(2000L + i, "Glory $i"), TODAY - d, 60 + i)
        fake.add(fake.track(1), TODAY - 40, 60)
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val messages = mutableListOf<String>()
        val scrolls = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.scrollToDay.collect { scrolls += it } }
        vm.onQueryChange("glory"); advanceUntilIdle()
        assertThat(vm.state.value.hasOlder).isTrue()

        vm.jumpTo(TODAY - 40); advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.noMatches).isFalse()
        assertThat(s.loading).isFalse()
        // The oldest matches, nearest the day asked for, with newer ones above to page up to.
        assertThat(s.days.last().day).isEqualTo(TODAY - 29)
        assertThat(s.hasNewer).isTrue()
        assertThat(scrolls.last()).isEqualTo(TODAY - 29)
        assertThat(messages.last()).startsWith("No matches on or before")
    }

    @Test fun `a list scrolled deeper than one reload chunk keeps its depth when read again`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 100) } // 3000 listens
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        repeat(12) { vm.loadOlder(); advanceUntilIdle() } // 2600 rows
        val depth = vm.state.value.days.sumOf { it.rows.size }
        assertThat(depth).isGreaterThan(HistoryViewModel.MAX_WINDOW_RELOAD)

        // A mirrored play lands in a loaded day in the past: the stretch is read again.
        val mirrored = fake.add(fake.track(9), TODAY - 10, minute = 5, origin = "web-device")
        advanceUntilIdle()
        val ids = vm.state.value.days.flatMap { it.rows }.map { it.event.id }
        assertThat(ids).contains(mirrored.event.id)
        assertThat(ids.size).isAtLeast(depth)
        assertThat(ids).containsNoDuplicates()
    }

    @Test fun `a new search scrolls to its top`() = runTest {
        val fake = FakeHistory().apply { fill(days = 3, perDay = 3) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val scrolls = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.scrollToDay.collect { scrolls += it } }
        vm.onQueryChange("song"); advanceUntilIdle()
        assertThat(scrolls).containsExactly(Long.MAX_VALUE)
    }

    @Test fun `the search survives process death`() = runTest {
        val fake = FakeHistory().apply {
            add(track(1, "Glory Box"), TODAY, 60)
            add(track(2, "Teardrop"), TODAY, 70)
        }
        val handle = SavedStateHandle()
        val first = viewModel(fake.repository(), handle)
        advanceUntilIdle()
        first.onQueryChange("glory"); advanceUntilIdle()

        val restored = viewModel(fake.repository(), handle)
        advanceUntilIdle()
        assertThat(restored.state.value.query).isEqualTo("glory")
        assertThat(restored.state.value.days.flatMap { it.rows }.map { it.track.title }).containsExactly("Glory Box")
    }

    @Test fun `paused with no listens shows the paused card only`() = runTest {
        paused.value = true
        val vm = viewModel(FakeHistory().repository())
        advanceUntilIdle()
        assertThat(vm.state.value.coldStart).isTrue()
        assertThat(vm.state.value.showColdStart).isFalse()
        assertThat(leadingKeys(vm.state.value)).containsExactly("paused")
    }

    @Test fun `a jump or a search replaces the list, paging up grows the same one`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val first = vm.state.value.listId
        vm.jumpTo(TODAY - 25); advanceUntilIdle()
        val jumped = vm.state.value.listId
        assertThat(jumped).isNotEqualTo(first)
        vm.loadNewer(); advanceUntilIdle()
        assertThat(vm.state.value.listId).isEqualTo(jumped)
        vm.onQueryChange("song"); advanceUntilIdle()
        assertThat(vm.state.value.listId).isNotEqualTo(jumped)
    }
}
