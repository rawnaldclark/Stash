package com.stash.feature.listening.stats

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.entity.ArtistImageEntity
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningRange
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.feature.listening.FakeHistory
import com.stash.feature.listening.FakeHistory.Companion.TODAY
import com.stash.feature.listening.SONG_NEEDS_CONNECTION
import com.stash.feature.listening.TOP_NEEDS_CONNECTION
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import java.time.DayOfWeek

/** "Your listening": ranges, the delta rule, cold start, top lists and what Play plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningStatsViewModelTest {

    private val player = mockk<PlayerRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>().also { every { it.isConnected() } returns true }
    private val photos = MutableStateFlow<List<ArtistImageEntity>>(emptyList())
    private val artistImages = mockk<ArtistImageDao>().also { every { it.observeAll() } returns photos }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(fake: FakeHistory, range: String? = null): ListeningStatsViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val handle = SavedStateHandle(if (range != null) mapOf(ListeningStatsViewModel.ARG_RANGE to range) else emptyMap())
        return ListeningStatsViewModel(handle, fake.repository(), artistImages, player, connectivity, DayOfWeek.MONDAY, dispatcher).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }
    }

    /** [n] listens on [day], song ids from [firstId]. */
    private fun FakeHistory.listens(day: Long, n: Int, firstId: Long = 1, minute: Int = 600) {
        repeat(n) { add(track(firstId + it), day, minute + it) }
    }

    @Test fun `opens on 30 days with the totals of those days`() = runTest {
        val fake = FakeHistory().apply {
            listens(TODAY, 6)
            listens(TODAY - 10, 6, firstId = 10)
            listens(TODAY - 40, 5, firstId = 20) // outside 30 days
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.range).isEqualTo(ListeningRange.Days30)
        assertThat(s.days).isEqualTo(DayRange(TODAY - 29, TODAY))
        assertThat(s.totals.plays).isEqualTo(12)
        assertThat(s.buckets).hasSize(30)
        assertThat(s.coldStart).isFalse()
        assertThat(s.estimated).isTrue() // the fake's listens have no real time
    }

    @Test fun `switching the range re-reads and is kept for process death`() = runTest {
        val fake = FakeHistory().apply {
            listens(TODAY, 10)
            listens(TODAY - 40, 5, firstId = 20)
        }
        val handle = SavedStateHandle()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vm = ListeningStatsViewModel(handle, fake.repository(), artistImages, player, connectivity, DayOfWeek.MONDAY, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertThat(vm.state.value.totals.plays).isEqualTo(10)

        vm.selectRange(ListeningRange.Months3)
        assertThat(vm.range.value).isEqualTo(ListeningRange.Months3)
        advanceUntilIdle()
        assertThat(vm.state.value.range).isEqualTo(ListeningRange.Months3)
        assertThat(vm.state.value.totals.plays).isEqualTo(15)
        assertThat(handle.get<String>(ListeningStatsViewModel.ARG_RANGE)).isEqualTo("3m")
        // 3 months and longer get the heatmap; 30 days doesn't.
        assertThat(vm.state.value.heatmap).isNotEmpty()

        vm.selectRange(ListeningRange.AllTime)
        advanceUntilIdle()
        assertThat(vm.state.value.days).isEqualTo(DayRange(TODAY - 40, TODAY))
        assertThat(vm.state.value.deltaPercent).isNull() // nothing before all time
    }

    @Test fun `a range from the route opens on that range, an unknown one on 30 days`() = runTest {
        val fake = FakeHistory().apply { listens(TODAY, 10) }
        assertThat(viewModel(fake, range = "7d").range.value).isEqualTo(ListeningRange.Days7)
        assertThat(viewModel(fake, range = "nope").range.value).isEqualTo(ListeningRange.Days30)
    }

    @Test fun `the delta compares complete days, and only when history covers the earlier window`() = runTest {
        // 7 days: the 7 complete days ending yesterday (today-7..today-1) vs today-14..today-8.
        val covered = FakeHistory().apply {
            listens(TODAY, 40, firstId = 200) // today is still going: not compared
            listens(TODAY - 3, 15)
            listens(TODAY - 9, 10, firstId = 30)
            add(track(99), TODAY - 14, 60) // the first listen opens the earlier window
        }
        val vm = viewModel(covered, range = "7d")
        advanceUntilIdle()
        // 15 songs against 11 (same length each): +36%, however much today holds.
        assertThat(vm.state.value.deltaPercent).isEqualTo(36)

        val partial = FakeHistory().apply {
            listens(TODAY, 15)
            listens(TODAY - 9, 10, firstId = 30)
            add(track(99), TODAY - 13, 60) // history starts one day into the earlier window
        }
        val vm2 = viewModel(partial, range = "7d")
        advanceUntilIdle()
        assertThat(vm2.state.value.deltaPercent).isNull()
    }

    @Test fun `under ten listens is a cold start`() = runTest {
        val fake = FakeHistory().apply { listens(TODAY, 9) }
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value.coldStart).isTrue()
        assertThat(vm.state.value.coversWholeHistory).isTrue()
        fake.add(fake.track(50), TODAY, 900)
        advanceUntilIdle()
        assertThat(vm.state.value.coldStart).isFalse()
    }

    @Test fun `a quiet week in a long history is a cold start that suggests a longer range`() = runTest {
        val fake = FakeHistory().apply {
            listens(TODAY - 20, 30)
            listens(TODAY, 2, firstId = 100)
        }
        val vm = viewModel(fake, range = "7d")
        advanceUntilIdle()
        assertThat(vm.state.value.coldStart).isTrue()
        assertThat(vm.state.value.coversWholeHistory).isFalse()
    }

    @Test fun `days, streaks, clock and highlights`() = runTest {
        val fake = FakeHistory().apply {
            listens(TODAY, 3, minute = 21 * 60)
            listens(TODAY - 1, 3, minute = 21 * 60)
            listens(TODAY - 2, 8, firstId = 40, minute = 9 * 60) // busiest day
            // An obsession: song 7 four times on today-5.
            repeat(4) { add(track(7), TODAY - 5, 22 * 60 + it) }
            add(track(1), TODAY - 60, 600) // song 1 heard before the range: not new
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.currentStreak).isEqualTo(3)
        assertThat(s.longestStreak?.days).isEqualTo(3)
        assertThat(s.busiestDay?.day).isEqualTo(TODAY - 2)
        assertThat(s.peakHour).isEqualTo(9)
        assertThat(s.daySplit!!.let { it.morning + it.afternoon + it.evening + it.night }).isEqualTo(100)
        assertThat(s.obsession?.track?.id).isEqualTo(7L)
        assertThat(s.obsession?.plays).isEqualTo(4)
        // Songs 2, 3, 7 and 40..47 are new in these 30 days; song 1 isn't.
        assertThat(s.newSongs).isEqualTo(11)
        assertThat(s.bestNewFind?.track?.id).isEqualTo(7L)
    }

    @Test fun `over the whole history there is no new-to-you, and two plays in a day isn't on repeat`() = runTest {
        val fake = FakeHistory().apply {
            listens(TODAY, 10)
            add(track(1), TODAY, 900)
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value.newSongs).isEqualTo(0)
        assertThat(vm.state.value.obsession).isNull()
    }

    @Test fun `top artists group by main act and carry the library's photo`() = runTest {
        val fake = FakeHistory().apply {
            repeat(3) { add(track(1, artist = "Drake"), TODAY, 600 + it) }
            repeat(2) { add(track(2, artist = "Drake, Future"), TODAY, 700 + it) }
            repeat(4) { add(track(3, artist = "Taro"), TODAY, 800 + it) }
            add(track(4, artist = "Else"), TODAY, 900)
        }
        photos.value = listOf(ArtistImageEntity(artistName = "drake", imageUrl = "photo://drake"))
        val vm = viewModel(fake)
        advanceUntilIdle()
        val artists = vm.state.value.topArtists
        assertThat(artists.map { it.name }).containsExactly("Drake", "Taro", "Else").inOrder()
        assertThat(artists[0].plays).isEqualTo(5)
        assertThat(artists[0].songs).isEqualTo(2)
        assertThat(artists[0].listenedMs).isEqualTo(5 * 200_000L)
        assertThat(artists[0].photoUrl).isEqualTo("photo://drake")
        assertThat(artists[1].photoUrl).isNull()
    }

    @Test fun `the artists count is main acts, the same as the list`() = runTest {
        val fake = FakeHistory().apply {
            repeat(3) { add(track(1, artist = "Drake"), TODAY, 600 + it) }
            repeat(2) { add(track(2, artist = "Drake, Future"), TODAY, 700 + it) }
            repeat(4) { add(track(3, artist = "Taro"), TODAY, 800 + it) }
            add(track(4, artist = "Else"), TODAY, 900)
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.totals.artists).isEqualTo(4) // credits, as stored
        assertThat(s.artistCount).isEqualTo(3) // Drake, Taro, Else
        assertThat(s.topArtists).hasSize(3)
    }

    @Test fun `two library rows of one song are one top song`() = runTest {
        val fake = FakeHistory().apply {
            repeat(4) { add(track(5, title = "Garden", artist = "Death Plus"), TODAY, 600 + it) }
            repeat(3) { add(track(6, title = "garden", artist = "Death Plus"), TODAY, 700 + it) }
            repeat(5) { add(track(7, title = "Other"), TODAY, 800 + it) }
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val songs = vm.state.value.topSongs
        assertThat(songs.map { it.track.id }).containsExactly(5L, 7L).inOrder()
        assertThat(songs[0].plays).isEqualTo(7)
        assertThat(songs[0].listenedMs).isEqualTo(7 * 200_000L)
    }

    @Test fun `top albums group by main act and name the main act`() = runTest {
        val fake = FakeHistory().apply {
            repeat(3) { add(track(1, artist = "Taro", album = "Blue"), TODAY, 600 + it) }
            repeat(2) { add(track(2, artist = "Taro, Mira", album = "Blue"), TODAY, 700 + it) }
            repeat(6) { add(track(3, artist = "Solo", album = "Red"), TODAY, 800 + it) }
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val albums = vm.state.value.topAlbums
        assertThat(albums.map { it.album to it.artist }).containsExactly("Red" to "Solo", "Blue" to "Taro").inOrder()
        assertThat(albums[1].plays).isEqualTo(5)
    }

    @Test fun `Play plays the top songs from number one, labelled with the range`() = runTest {
        val fake = FakeHistory().apply {
            repeat(3) { add(track(1), TODAY, 600 + it) }
            repeat(2) { add(track(2), TODAY, 700 + it) }
            listens(TODAY, 6, firstId = 10)
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        coEvery { player.setQueue(capture(queue), capture(start), any()) } returns Unit
        vm.playTopSongs(); advanceUntilIdle()
        assertThat(queue.captured.take(2).map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(queue.captured).hasSize(8)
        assertThat(start.captured).isEqualTo(0)
        coVerify { player.setQueue(any(), any(), PlaybackSource.Listening("Top songs · 30 days")) }

        vm.playTopSong(2L); advanceUntilIdle()
        assertThat(start.captured).isEqualTo(1)
        assertThat(queue.captured).hasSize(8)
    }

    @Test fun `offline, Play keeps only downloaded songs and a tapped streamed song says so`() = runTest {
        every { connectivity.isConnected() } returns false
        val fake = FakeHistory().apply {
            repeat(3) { add(track(1), TODAY, 600 + it) }
            repeat(2) { add(track(2, filePath = "/music/2.opus"), TODAY, 700 + it) }
            listens(TODAY, 6, firstId = 10)
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        runCurrent()
        val queue = slot<List<Track>>()
        coEvery { player.setQueue(capture(queue), any(), any()) } returns Unit

        vm.playTopSongs(); advanceUntilIdle()
        assertThat(queue.captured.map { it.id }).containsExactly(2L)

        vm.playTopSong(1L); advanceUntilIdle()
        assertThat(messages).containsExactly(SONG_NEEDS_CONNECTION)
    }

    @Test fun `offline with nothing downloaded, Play says so`() = runTest {
        every { connectivity.isConnected() } returns false
        val fake = FakeHistory().apply { listens(TODAY, 10) }
        val vm = viewModel(fake)
        advanceUntilIdle()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        runCurrent()
        vm.playTopSongs(); advanceUntilIdle()
        assertThat(messages).containsExactly(TOP_NEEDS_CONNECTION)
        coVerify(exactly = 0) { player.setQueue(any(), any(), any()) }
    }
}
