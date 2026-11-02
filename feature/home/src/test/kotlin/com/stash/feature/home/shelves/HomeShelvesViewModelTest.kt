package com.stash.feature.home.shelves

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.home.BecauseRow
import com.stash.core.data.home.BecauseSeed
import com.stash.core.data.home.BecauseYouLikeRepository
import com.stash.core.data.home.HomeShownSongs
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.home.NewFromArtistsRepository
import com.stash.core.data.prefs.DEFAULT_HOME_SECTIONS
import com.stash.core.data.prefs.HomeSection
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.MoodTile
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * [HomeShelvesViewModel]'s wiring: a shelf loads only while it's on Home (Streaming on, its section shown), with no
 * network it shows what's saved without trying, and it stops loading when it's hidden or Offline mode turns on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeShelvesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val releases = listOf(AlbumSummary("MPREb_1", "Bring Your Love", "Sabrina Carpenter", null, "2026"))
    private val saved = listOf(AlbumSummary("MPREb_0", "Manchild", "Sabrina Carpenter", null, "2025"))

    private val online = MutableStateFlow(true)
    private val visible = MutableStateFlow(DEFAULT_HOME_SECTIONS)
    private val newFromArtists = mock<NewFromArtistsRepository> {
        onBlocking { cached() } doReturn saved
        onBlocking { shelf() } doReturn releases
    }
    private val tiles = listOf(MoodTile("Chill", "ggMPOg1uX1JOQWZFeDByc2Jm", 4288988671L))
    private val moods = mock<MoodsRepository> {
        onBlocking { cached() } doReturn emptyList()
        onBlocking { tiles() } doReturn tiles
    }
    private fun song(n: Int) = TrackSummary("v$n", "Song $n", "Artist $n", "Album $n", 200.0, "https://art/$n", musicVideoType = "ATV")
    private val row = BecauseRow(BecauseSeed("seed", "Africa", "Toto", null), (1..6).map(::song))
    private val because = mock<BecauseYouLikeRepository> {
        onBlocking { cached() } doReturn emptyList()
        onBlocking { shelf() } doReturn listOf(row)
    }
    private val shown = HomeShownSongs()
    private val player = mock<PlayerRepository>()
    private val streaming = mock<StreamingPreference> { on { enabled } doReturn online }
    private val sections = mock<HomeSectionsPreference> { on { visibleSections } doReturn visible }
    private val connectivity = mock<ConnectivityMonitor> { on { isConnected() } doReturn true }

    private fun viewModel() = HomeShelvesViewModel(newFromArtists, moods, because, shown, player, streaming, sections, connectivity)

    @Test
    fun `shown and online, the row is built and shown`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
        verifyBlocking(newFromArtists, times(1)) { shelf() }
    }

    @Test
    fun `a hidden section costs no request, and turning it back on fills it`() = runTest(dispatcher) {
        visible.value = DEFAULT_HOME_SECTIONS - HomeSection.NEW_FROM_ARTISTS
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        verifyBlocking(newFromArtists, never()) { shelf() }
        verifyBlocking(newFromArtists, never()) { cached() }
        assertThat(vm.state.value.newFromArtists).isEmpty()
        // Turned back on in Settings > Home layout
        visible.value = DEFAULT_HOME_SECTIONS
        runCurrent()
        verifyBlocking(newFromArtists, times(1)) { shelf() }
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
    }

    @Test
    fun `offline mode shows nothing and builds nothing, and switching it off fills the row`() = runTest(dispatcher) {
        online.value = false
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(HomeShelvesState())
        verifyBlocking(newFromArtists, never()) { shelf() }
        online.value = true
        runCurrent()
        verifyBlocking(newFromArtists, times(1)) { shelf() }
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
    }

    @Test
    fun `a build under way stops when offline mode turns on`() = runTest(dispatcher) {
        val never = CompletableDeferred<List<AlbumSummary>>()
        var cancelled = false
        whenever(newFromArtists.shelf()).doSuspendableAnswer {
            try {
                never.await()
            } finally {
                cancelled = true
            }
        }
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(cancelled).isFalse()
        online.value = false
        runCurrent()
        assertThat(cancelled).isTrue()
        assertThat(vm.state.value.newFromArtists).isEmpty()
    }

    @Test
    fun `each shelf follows its own section - moods hidden costs nothing while your artists' row loads`() = runTest(dispatcher) {
        visible.value = DEFAULT_HOME_SECTIONS - HomeSection.MOODS
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
        assertThat(vm.state.value.moods).isEmpty()
        verifyBlocking(moods, never()) { tiles() }
        visible.value = DEFAULT_HOME_SECTIONS
        runCurrent()
        assertThat(vm.state.value.moods).isEqualTo(tiles)
        // Only the shelf that was turned on loads again
        verifyBlocking(newFromArtists, times(1)) { shelf() }
    }

    @Test
    fun `with no network the saved row shows and no build is tried`() = runTest(dispatcher) {
        whenever(connectivity.isConnected()).doReturn(false)
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(saved)
        verifyBlocking(newFromArtists, never()) { shelf() }
        // Back on a network: the next return to Home builds
        whenever(connectivity.isConnected()).doReturn(true)
        vm.onResume()
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
    }

    // ── Because you like ───────────────────────────────────────────────────

    @Test
    fun `because you like shows its rows, less what the record's mixes on Home hold`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(vm.state.value.becauseYouLike).containsExactly(row)
        shown.publish(HomeShownSongs.RECORD_MIXES, listOf("Song 1" to "Artist 1"))
        runCurrent()
        assertThat(vm.state.value.becauseYouLike.single().songs).isEqualTo(row.songs.drop(1))
        // Three more taken: the row is under four cards and goes
        shown.publish(HomeShownSongs.RECORD_MIXES, (1..3).map { "Song $it" to "Artist $it" })
        runCurrent()
        assertThat(vm.state.value.becauseYouLike).isEmpty()
    }

    @Test
    fun `because you like hidden costs no request`() = runTest(dispatcher) {
        visible.value = DEFAULT_HOME_SECTIONS - HomeSection.BECAUSE_YOU_LIKE
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        verifyBlocking(because, never()) { shelf() }
        assertThat(vm.state.value.becauseYouLike).isEmpty()
    }

    @Test
    fun `a card plays its row from that song, streamed, named for the seed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.playRow(row, 2, "Because you like Africa")
        runCurrent()
        val queue = argumentCaptor<List<Track>>()
        verifyBlocking(player) { setQueue(queue.capture(), eq(2), eq(PlaybackSource.Listening("Because you like Africa"))) }
        assertThat(queue.firstValue.map { it.youtubeId }).isEqualTo(row.songs.map { it.videoId })
        assertThat(queue.firstValue.all { it.source == MusicSource.YOUTUBE && it.isStreamable }).isTrue()
        assertThat(queue.firstValue[2].durationMs).isEqualTo(200_000L)
    }
}
