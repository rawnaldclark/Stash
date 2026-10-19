package com.stash.feature.listening.top

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.listening.ListeningRange
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import kotlin.random.Random

/** Top: kinds, ranges, ranked rows with time, and Play / Shuffle / tap. */
@OptIn(ExperimentalCoroutinesApi::class)
class TopViewModelTest {

    private val player = mockk<PlayerRepository>(relaxed = true).also {
        every { it.playerState } returns MutableStateFlow(PlayerState())
    }
    private val connectivity = mockk<ConnectivityMonitor>().also { every { it.isConnected() } returns true }
    private val artistImages = mockk<ArtistImageDao>().also { every { it.observeAll() } returns flowOf(emptyList()) }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        fake: FakeHistory,
        args: Map<String, Any?> = emptyMap(),
        handle: SavedStateHandle = SavedStateHandle(args),
        genres: ArtistGenreIndex = ArtistGenreIndex.EMPTY,
    ): TopViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return TopViewModel(handle, fake.repository(), artistImages, player, connectivity, Random(7), dispatcher, flowOf(genres)).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }
    }

    private fun library() = FakeHistory().apply {
        repeat(3) { add(track(1, artist = "Taro", album = "Blue"), TODAY, 600 + it) }
        repeat(2) { add(track(2, artist = "Taro", album = "Blue"), TODAY - 3, 600 + it, listenedMs = 60_000) }
        add(track(3, artist = "Mira", album = "Red"), TODAY - 2, 600)
        add(track(4, artist = "Old", album = "Gone"), TODAY - 50, 600) // outside 30 days
    }

    @Test fun `songs over 30 days by default, ranked, with plays and time`() = runTest {
        val vm = viewModel(library())
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.kind).isEqualTo(TopKind.SONGS)
        assertThat(s.range).isEqualTo(ListeningRange.Days30)
        assertThat(s.songs.map { it.track.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(s.songs.map { it.plays }).containsExactly(3, 2, 1).inOrder()
        assertThat(s.songs[1].listenedMs).isEqualTo(120_000L) // real time
        assertThat(s.songs[0].listenedMs).isEqualTo(600_000L) // estimated from the length
        assertThat(s.estimated).isTrue()
    }

    @Test fun `kind and range come from the route, and switching keeps them for process death`() = runTest {
        val handle = SavedStateHandle(mapOf(TopViewModel.ARG_KIND to "artists", TopViewModel.ARG_RANGE to "7d"))
        val vm = viewModel(library(), handle = handle)
        advanceUntilIdle()
        assertThat(vm.state.value.kind).isEqualTo(TopKind.ARTISTS)
        assertThat(vm.state.value.artists.map { it.name }).containsExactly("Taro", "Mira").inOrder()
        assertThat(vm.state.value.artists[0].plays).isEqualTo(5)
        assertThat(vm.state.value.artists[0].listenedMs).isEqualTo(720_000L)

        vm.selectKind(TopKind.ALBUMS)
        vm.selectRange(ListeningRange.AllTime)
        advanceUntilIdle()
        assertThat(vm.state.value.albums.map { it.album }).containsExactly("Blue", "Red", "Gone").inOrder()
        assertThat(handle.get<String>(TopViewModel.ARG_KIND)).isEqualTo("albums")
        assertThat(handle.get<String>(TopViewModel.ARG_RANGE)).isEqualTo("all")
    }

    @Test fun `an unknown kind opens on songs`() = runTest {
        assertThat(TopKind.fromKey("moods")).isEqualTo(TopKind.SONGS)
        assertThat(TopKind.fromKey("genres")).isEqualTo(TopKind.GENRES)
        assertThat(TopKind.fromKey(null)).isEqualTo(TopKind.SONGS)
        assertThat(TopKind.fromKey("albums")).isEqualTo(TopKind.ALBUMS)
    }

    @Test fun `nothing in the range is empty`() = runTest {
        val vm = viewModel(FakeHistory().apply { add(track(1), TODAY - 50, 600) }, mapOf(TopViewModel.ARG_RANGE to "7d"))
        advanceUntilIdle()
        assertThat(vm.state.value.isEmpty).isTrue()
    }

    @Test fun `Play, Shuffle and a tapped song`() = runTest {
        val vm = viewModel(library())
        advanceUntilIdle()
        val queue = slot<List<Track>>()
        val start = slot<Int>()
        coEvery { player.setQueue(capture(queue), capture(start), any()) } returns Unit

        vm.play(); advanceUntilIdle()
        assertThat(queue.captured.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(start.captured).isEqualTo(0)
        coVerify { player.setQueue(any(), any(), PlaybackSource.Listening("Top songs · 30 days")) }

        vm.playFrom(3L); advanceUntilIdle()
        assertThat(start.captured).isEqualTo(2)

        vm.shuffle(); advanceUntilIdle()
        assertThat(queue.captured.map { it.id }).containsExactly(1L, 2L, 3L)
        assertThat(queue.captured.map { it.id }).isEqualTo(listOf(1L, 2L, 3L).shuffled(Random(7)))
        assertThat(start.captured).isEqualTo(0)
    }

    @Test fun `a tap on a song that has left the list plays nothing`() = runTest {
        val vm = viewModel(library())
        advanceUntilIdle()
        vm.playFrom(999L); advanceUntilIdle()
        coVerify(exactly = 0) { player.setQueue(any(), any(), any()) }
    }

    @Test fun `songs merge library duplicates, albums group by main act`() = runTest {
        val fake = FakeHistory().apply {
            repeat(2) { add(track(1, title = "Garden", artist = "Death Plus", isrc = "USX1"), TODAY, 600 + it) }
            repeat(3) { add(track(2, title = "Garden (Remastered)", artist = "Death Plus", isrc = "usx1"), TODAY, 700 + it) }
            add(track(3, title = "Solo"), TODAY, 800)
        }
        val vm = viewModel(fake)
        advanceUntilIdle()
        assertThat(vm.state.value.songs.map { it.track.id }).containsExactly(2L, 3L).inOrder()
        assertThat(vm.state.value.songs[0].plays).isEqualTo(5)

        val albums = FakeHistory().apply {
            repeat(2) { add(track(1, artist = "Taro", album = "Blue"), TODAY, 600 + it) }
            add(track(2, artist = "Taro, Mira", album = "Blue"), TODAY, 700)
        }
        val vm2 = viewModel(albums, mapOf(TopViewModel.ARG_KIND to "albums"))
        advanceUntilIdle()
        assertThat(vm2.state.value.albums.map { it.album to it.artist }).containsExactly("Blue" to "Taro")
        assertThat(vm2.state.value.albums[0].plays).isEqualTo(3)
    }

    @Test fun `Play does nothing on artists`() = runTest {
        val vm = viewModel(library(), mapOf(TopViewModel.ARG_KIND to "artists"))
        advanceUntilIdle()
        vm.play(); advanceUntilIdle()
        coVerify(exactly = 0) { player.setQueue(any(), any(), any()) }
    }

    @Test fun `offline, a tapped song that needs a connection says so`() = runTest {
        every { connectivity.isConnected() } returns false
        val vm = viewModel(library())
        advanceUntilIdle()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        runCurrent()
        vm.playFrom(1L); advanceUntilIdle()
        assertThat(messages).containsExactly(SONG_NEEDS_CONNECTION)
        coVerify(exactly = 0) { player.setQueue(any(), any(), any()) }
    }

    @Test fun `genres rank by listening time, each with its top artists`() = runTest {
        val index = ArtistGenreIndex.of(
            mapOf(
                "Taro" to listOf(WeightedGenre("jazz", 1f)),
                "Mira" to listOf(WeightedGenre("jazz", 1f), WeightedGenre("soul", 1f)),
            ),
        )
        val vm = viewModel(library(), mapOf(TopViewModel.ARG_KIND to "genres"), genres = index)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.kind).isEqualTo(TopKind.GENRES)
        // Taro 720 000 ms of jazz; Mira 200 000 ms split half jazz, half soul.
        assertThat(s.genres.map { it.genre.key }).containsExactly("jazz", "soul").inOrder()
        assertThat(s.genres[0].artists).containsExactly("Taro", "Mira").inOrder()
        assertThat(s.genres[1].artists).containsExactly("Mira")
        assertThat(s.genreCoverage).isWithin(1e-9).of(1.0)
        assertThat(s.genresTooThin).isFalse()
        assertThat(s.isEmpty).isFalse()
    }

    @Test fun `genres known for under half the listening say so instead of ranking`() = runTest {
        val index = ArtistGenreIndex.of(mapOf("Mira" to listOf(WeightedGenre("soul", 1f))))
        val vm = viewModel(library(), mapOf(TopViewModel.ARG_KIND to "genres"), genres = index)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.genreCoverage).isLessThan(0.5)
        assertThat(s.genresTooThin).isTrue()
        assertThat(s.genresCached).isTrue()
        assertThat(s.isEmpty).isFalse()
    }

    @Test fun `a featured credit counts for its act, not as a second artist`() = runTest {
        val fake = FakeHistory().apply {
            repeat(3) { add(track(1, artist = "Drake"), TODAY, 600 + it) }
            repeat(2) { add(track(2, artist = "Drake feat. Rihanna"), TODAY, 700 + it) }
            add(track(3, artist = "Earth, Wind & Fire, Chicago"), TODAY, 800)
        }
        val index = ArtistGenreIndex.of(
            mapOf(
                "Drake" to listOf(WeightedGenre("hip-hop", 1f)),
                "Earth, Wind & Fire" to listOf(WeightedGenre("funk", 1f)),
            ),
        )
        val vm = viewModel(fake, mapOf(TopViewModel.ARG_KIND to "genres"), genres = index)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.genres.map { it.genre.key }).containsExactly("hip-hop", "funk").inOrder()
        assertThat(s.genres[0].artists).containsExactly("Drake") // one act, not two
        assertThat(s.genres[0].genre.share).isWithin(1e-9).of(5.0 / 6) // 5 of 6 listens, same length
        assertThat(s.genres[1].artists).containsExactly("Earth, Wind & Fire") // not dropped
        assertThat(s.genreCoverage).isWithin(1e-9).of(1.0)
    }

    @Test fun `genres of a range with no listens are empty`() = runTest {
        val vm = viewModel(FakeHistory(), mapOf(TopViewModel.ARG_KIND to "genres"))
        advanceUntilIdle()
        assertThat(vm.state.value.isEmpty).isTrue()
    }
}
