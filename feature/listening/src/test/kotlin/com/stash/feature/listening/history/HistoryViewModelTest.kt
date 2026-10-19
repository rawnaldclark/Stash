package com.stash.feature.listening.history

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listening.ListeningHistoryPreference
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.feature.listening.FakeHistory
import com.stash.feature.listening.FakeHistory.Companion.TODAY
import com.stash.feature.listening.SONG_NEEDS_CONNECTION
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val paused = MutableStateFlow(false)
    private val preference = mockk<ListeningHistoryPreference>(relaxed = true).also {
        every { it.paused } returns paused
    }
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>().also { every { it.isConnected() } returns true }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        history: ListeningHistoryRepository,
        day: Long? = null,
    ): HistoryViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle(if (day != null) mapOf(HistoryViewModel.ARG_DAY to day) else emptyMap())
        return HistoryViewModel(
            handle, history, preference, player,
            mockk(relaxed = true) { every { getUserCreatedPlaylists() } returns flowOf(emptyList()) },
            connectivity, { ZoneOffset.UTC },
        )
    }

    // ── Paging ───────────────────────────────────────────────────────

    @Test fun `opens on the newest listens grouped by day, and pages down without repeats`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) } // 600 listens
        val vm = viewModel(fake.repository())
        advanceUntilIdle()

        val s = vm.state.value
        assertThat(s.loading).isFalse()
        assertThat(s.days.first().day).isEqualTo(TODAY)
        assertThat(s.days.sumOf { it.rows.size }).isEqualTo(200)
        assertThat(s.days.map { it.day }).isInOrder(Comparator<Long> { a, b -> b.compareTo(a) })
        assertThat(s.hasOlder).isTrue()
        assertThat(s.hasNewer).isFalse()
        assertThat(s.dayCounts[TODAY]?.plays).isEqualTo(20)

        vm.loadOlder(); advanceUntilIdle()
        vm.loadOlder(); advanceUntilIdle()
        vm.loadOlder(); advanceUntilIdle()
        val ids = vm.state.value.days.flatMap { it.rows }.map { it.event.id }
        assertThat(ids).hasSize(600)
        assertThat(ids).containsNoDuplicates()
        assertThat(vm.state.value.hasOlder).isFalse()
    }

    @Test fun `a loaded day only scrolls, without reloading`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val calls = fake.pageCalls.size
        val scrolls = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.scrollToDay.collect { scrolls += it } }
        runCurrent()

        vm.jumpTo(TODAY - 3); advanceUntilIdle()
        assertThat(scrolls).containsExactly(TODAY - 3)
        assertThat(fake.pageCalls.size).isEqualTo(calls)
    }

    @Test fun `jumping to an unloaded day restarts the list there, then pages up to today`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val scrolls = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.scrollToDay.collect { scrolls += it } }
        runCurrent()

        val target = TODAY - 25
        vm.jumpTo(target); advanceUntilIdle()
        var s = vm.state.value
        assertThat(s.days.first().day).isEqualTo(target)
        assertThat(s.days.first().rows).hasSize(20) // the whole day, from its last listen
        assertThat(s.hasNewer).isTrue()
        assertThat(scrolls).containsExactly(target)

        // Scrolling up pages towards today until the newest listen is in.
        repeat(5) { vm.loadNewer(); advanceUntilIdle() }
        s = vm.state.value
        assertThat(s.hasNewer).isFalse()
        assertThat(s.days.first().day).isEqualTo(TODAY)
        val ids = s.days.flatMap { it.rows }.map { it.event.id }
        assertThat(ids).containsNoDuplicates()
        // Newest first all the way down.
        val starts = s.days.flatMap { it.rows }.map { it.event.startedAt }
        assertThat(starts).isInOrder(Comparator<Long> { a, b -> b.compareTo(a) })
    }

    @Test fun `opening on a day from the route starts there`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val vm = viewModel(fake.repository(), day = TODAY - 20)
        advanceUntilIdle()
        assertThat(vm.state.value.days.first().day).isEqualTo(TODAY - 20)
        assertThat(vm.state.value.hasNewer).isTrue()
    }

    @Test fun `back to latest after a jump shows the newest listens again`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        vm.jumpTo(TODAY - 25); advanceUntilIdle()
        vm.backToLatest(); advanceUntilIdle()
        assertThat(vm.state.value.days.first().day).isEqualTo(TODAY)
        assertThat(vm.state.value.hasNewer).isFalse()
    }

    @Test fun `a listen recorded while History is open slides in on top`() = runTest {
        val fake = FakeHistory().apply { fill(days = 2, perDay = 3) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val fresh = fake.add(fake.track(7), TODAY, minute = 900)
        advanceUntilIdle()
        assertThat(vm.state.value.days.first().rows.first().event.id).isEqualTo(fresh.event.id)
        assertThat(vm.state.value.days.first().rows).hasSize(4)
    }

    @Test fun `plays that land in the past while History is open (the web mirror) join their days`() = runTest {
        val fake = FakeHistory().apply { fill(days = 5, perDay = 2) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val mirrored = fake.add(fake.track(9), TODAY - 2, minute = 30, origin = "web-device")
        advanceUntilIdle()
        val day = vm.state.value.days.first { it.day == TODAY - 2 }
        assertThat(day.rows.map { it.event.id }).contains(mirrored.event.id)
        assertThat(day.rows).hasSize(3)
        assertThat(vm.state.value.days.flatMap { it.rows }.map { it.event.id }).containsNoDuplicates()
    }

    // ── Search ───────────────────────────────────────────────────────

    @Test fun `search waits 250 ms after the last keystroke and runs once`() = runTest {
        val fake = FakeHistory().apply {
            add(track(1, "Glory Box", "Portishead"), TODAY, 60)
            add(track(2, "Teardrop", "Massive Attack"), TODAY, 70)
        }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val before = fake.pageCalls.size

        vm.onQueryChange("g"); advanceTimeBy(100)
        vm.onQueryChange("gl"); advanceTimeBy(100)
        vm.onQueryChange("glory"); advanceTimeBy(249); runCurrent()
        assertThat(fake.pageCalls.size).isEqualTo(before)
        assertThat(vm.state.value.query).isEqualTo("glory") // the field shows it at once
        advanceTimeBy(2); runCurrent()
        advanceUntilIdle()
        assertThat(fake.pageCalls.drop(before).map { it.second }).containsExactly("glory")
        assertThat(vm.state.value.days.flatMap { it.rows }.map { it.track.title }).containsExactly("Glory Box")
    }

    @Test fun `clearing the search shows everything at once`() = runTest {
        val fake = FakeHistory().apply {
            add(track(1, "Glory Box"), TODAY, 60)
            add(track(2, "Teardrop"), TODAY, 70)
        }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        vm.onQueryChange("glory"); advanceUntilIdle()
        vm.onQueryChange(""); runCurrent()
        assertThat(vm.state.value.days.flatMap { it.rows }).hasSize(2)
    }

    @Test fun `a search with no match says so, and is not a cold start`() = runTest {
        val fake = FakeHistory().apply { add(track(1, "Glory Box"), TODAY, 60) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        vm.onQueryChange("zzz"); advanceUntilIdle()
        assertThat(vm.state.value.noMatches).isTrue()
        assertThat(vm.state.value.coldStart).isFalse()
    }

    @Test fun `no listens at all is the cold start`() = runTest {
        val vm = viewModel(FakeHistory().repository())
        advanceUntilIdle()
        assertThat(vm.state.value.coldStart).isTrue()
        assertThat(vm.state.value.noMatches).isFalse()
    }

    // ── Removing ─────────────────────────────────────────────────────

    @Test fun `remove from history takes that one listen out`() = runTest {
        val fake = FakeHistory().apply { fill(days = 2, perDay = 3) }
        val repo = fake.repository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val victim = vm.state.value.days.first().rows[1]

        vm.removeListen(victim); advanceUntilIdle()
        coVerify(exactly = 1) { repo.deleteListen(victim.event.id) }
        val ids = vm.state.value.days.flatMap { it.rows }.map { it.event.id }
        assertThat(ids).doesNotContain(victim.event.id)
        assertThat(ids).hasSize(5)
        assertThat(vm.state.value.dayCounts[TODAY]?.plays).isEqualTo(2)
    }

    @Test fun `remove this day takes the day out of the list`() = runTest {
        val fake = FakeHistory().apply { fill(days = 3, perDay = 3) }
        val repo = fake.repository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.removeDay(TODAY - 1); advanceUntilIdle()
        coVerify(exactly = 1) { repo.deleteDay(TODAY - 1) }
        assertThat(vm.state.value.days.map { it.day }).containsExactly(TODAY, TODAY - 2).inOrder()
    }

    @Test fun `a day cleared elsewhere leaves the open list`() = runTest {
        val fake = FakeHistory().apply { fill(days = 3, perDay = 3) }
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        fake.delete { it.event.localDay == TODAY - 2 } // Settings › Clear a date range
        advanceUntilIdle()
        assertThat(vm.state.value.days.map { it.day }).containsExactly(TODAY, TODAY - 1).inOrder()
    }

    // ── Paused ───────────────────────────────────────────────────────

    @Test fun `the paused card follows the setting, and Resume turns history back on`() = runTest {
        val vm = viewModel(FakeHistory().apply { fill(1, 1) }.repository())
        advanceUntilIdle()
        assertThat(vm.state.value.paused).isFalse()
        paused.value = true; advanceUntilIdle()
        assertThat(vm.state.value.paused).isTrue()
        vm.resumeHistory(); advanceUntilIdle()
        coVerify(exactly = 1) { preference.setPaused(false) }
    }

    // ── Playing ──────────────────────────────────────────────────────

    @Test fun `tapping a listen plays on down the list, into the days before`() = runTest {
        val fake = FakeHistory()
        val a = fake.track(1); val b = fake.track(2); val c = fake.track(3); val d = fake.track(4)
        fake.add(d, TODAY - 1, 50)
        fake.add(a, TODAY, 60)
        fake.add(b, TODAY, 65)
        fake.add(b, TODAY, 70) // repeat-one: once
        val tapped = fake.add(c, TODAY, 75)
        fake.add(a, TODAY, 80)
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        val source = slot<PlaybackSource>()
        coEvery { player.setQueue(capture(queue), capture(start), capture(source)) } returns Unit

        vm.play(tapped); advanceUntilIdle()
        // As the list reads, newest first: the row above stays for Previous.
        assertThat(queue.captured.map { it.id }).containsExactly(1L, 3L, 2L, 1L, 4L).inOrder()
        assertThat(start.captured).isEqualTo(1)
        assertThat((source.captured as PlaybackSource.Listening).label).isEqualTo("History")
    }

    @Test fun `the last song of a day still has a queue after it`() = runTest {
        // The owner's Oct 2: the top row of a day was its last song and played alone.
        val fake = FakeHistory()
        val first = fake.add(fake.track(1), TODAY - 2, 600)
        fake.add(fake.track(2), TODAY - 2, 610)
        val last = fake.add(fake.track(3), TODAY - 2, 620)
        fake.add(fake.track(4), TODAY - 3, 600)
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        coEvery { player.setQueue(capture(queue), capture(start), any()) } returns Unit

        vm.play(last); advanceUntilIdle()
        assertThat(queue.captured.map { it.id }).containsExactly(3L, 2L, 1L, 4L).inOrder()
        assertThat(start.captured).isEqualTo(0)

        vm.play(first); advanceUntilIdle()
        assertThat(queue.captured[start.captured].id).isEqualTo(1L)
        assertThat(queue.captured.drop(start.captured + 1).map { it.id }).containsExactly(4L)
    }

    @Test fun `a tap near the end of the loaded rows reads on so the queue doesn't stop there`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) } // 600 listens, 200 loaded
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val loaded = vm.state.value.days.flatMap { it.rows }
        assertThat(loaded).hasSize(200)
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        coEvery { player.setQueue(capture(queue), capture(start), any()) } returns Unit

        vm.play(loaded.last()); advanceUntilIdle()
        // The tapped row was the last loaded one: all 100 read ahead follow it.
        assertThat(queue.captured.size - start.captured - 1).isEqualTo(HistoryViewModel.QUEUE_READ_AHEAD)
        // Only the queue grew: the list on screen is as it was.
        assertThat(vm.state.value.days.sumOf { it.rows.size }).isEqualTo(200)
    }

    @Test fun `a failed read-ahead still plays the loaded rows`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val repo = fake.repository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val loaded = vm.state.value.days.flatMap { it.rows }
        coEvery { repo.historyPage(any(), any(), any()) } throws IllegalStateException("disk")
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        coEvery { player.setQueue(capture(queue), capture(start), any()) } returns Unit

        vm.play(loaded.last()); advanceUntilIdle()
        assertThat(queue.captured[start.captured].id).isEqualTo(loaded.last().track.id)
        assertThat(start.captured).isEqualTo(queue.captured.lastIndex)
    }

    @Test fun `a later tap wins over an earlier one still reading ahead`() = runTest {
        val fake = FakeHistory().apply { fill(days = 30, perDay = 20) }
        val repo = fake.repository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val loaded = vm.state.value.days.flatMap { it.rows }
        val pages = fake.pageCalls.size
        coEvery { repo.historyPage(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(1_000)
            fake.page(firstArg(), secondArg(), thirdArg())
        }
        val queues = mutableListOf<Int>()
        coEvery { player.setQueue(any(), capture(queues), any()) } returns Unit

        vm.play(loaded.last())   // reads ahead (slow)
        vm.play(loaded.first())  // no read-ahead
        advanceUntilIdle()
        // Only the second tap's queue reached the player.
        assertThat(queues).hasSize(1)
        assertThat(fake.pageCalls.size).isAtLeast(pages)
    }

    @Test fun `offline, a tapped song that isn't downloaded says so instead of playing`() = runTest {
        every { connectivity.isConnected() } returns false
        val fake = FakeHistory()
        val row = fake.add(fake.track(1), TODAY, 60)
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        runCurrent()
        vm.play(row); advanceUntilIdle()
        coVerify(exactly = 0) { player.setQueue(any(), any(), any()) }
        assertThat(messages).contains(SONG_NEEDS_CONNECTION)
    }

    @Test fun `play this day starts at its first song`() = runTest {
        val fake = FakeHistory()
        fake.add(fake.track(1), TODAY, 60)
        fake.add(fake.track(2), TODAY, 65)
        val vm = viewModel(fake.repository())
        advanceUntilIdle()
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        coEvery { player.setQueue(capture(queue), capture(start), any()) } returns Unit
        vm.playDay(TODAY); advanceUntilIdle()
        assertThat(queue.captured.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(start.captured).isEqualTo(0)
    }

    // ── Grouping ─────────────────────────────────────────────────────

    @Test fun `a day that appears twice (after travel) gets a second key`() {
        val fake = FakeHistory()
        val t = fake.track(1)
        val a = fake.add(t, TODAY, 600)
        val b = fake.add(t, TODAY, 500)
        val c = fake.add(t, TODAY, 400)
        // Heard between the other two but stamped a day earlier, in a zone behind this one.
        val travelled = b.copy(event = b.event.copy(localDay = TODAY - 1))
        val groups = HistoryViewModel.groupByDay(listOf(a, travelled, c))
        assertThat(groups.map { it.day }).containsExactly(TODAY, TODAY - 1, TODAY).inOrder()
        assertThat(groups.map { it.key }.toSet()).hasSize(3)
    }
}
