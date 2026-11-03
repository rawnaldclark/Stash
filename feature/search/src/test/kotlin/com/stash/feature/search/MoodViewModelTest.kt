package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodPlaylist
import com.stash.data.ytmusic.model.MoodShelf
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking

/**
 * [MoodViewModel]: a mood's or a genre's page reads YouTube only while Online. Offline (reachable from the back stack
 * after the switch) it reads nothing and says so, and Online opens it. A genre's Songs row plays from the song tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val streaming = MutableStateFlow(true)
    private val page = MoodPage(
        "Chill",
        listOf(MoodShelf("Coffee shop blends", listOf(MoodPlaylist("VLRDCLAK5uy_1", "Coffee Shop Blend", "YouTube Music", null)))),
    )
    private val moods = mock<MoodsRepository> { onBlocking { page(any(), any()) } doReturn page }
    private val player = mock<PlayerRepository>()

    private fun viewModel(repo: MoodsRepository = moods, params: String = "ggMPOg1uX1JOQWZFeDByc2Jm", title: String = "Chill") = MoodViewModel(
        SavedStateHandle(mapOf(MoodViewModel.ARG_PARAMS to params, MoodViewModel.ARG_TITLE to title)),
        repo,
        mock<StreamingPreference> { on { enabled } doReturn streaming },
        player,
    )

    @Test
    fun `Online, the page is read once and shown`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.shelves).isEqualTo(page.shelves)
        assertThat(vm.state.value.offline).isFalse()
        assertThat(vm.state.value.loading).isFalse()
        verifyBlocking(moods, times(1)) { page("ggMPOg1uX1JOQWZFeDByc2Jm", "Chill") }
    }

    @Test
    fun `Offline, it reads nothing and says so, and Online opens it`() = runTest(dispatcher) {
        streaming.value = false
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.offline).isTrue()
        assertThat(vm.state.value.loading).isFalse()
        // Try again does nothing either
        vm.retry()
        runCurrent()
        verifyBlocking(moods, never()) { page(any(), any()) }

        streaming.value = true
        runCurrent()
        assertThat(vm.state.value.offline).isFalse()
        assertThat(vm.state.value.shelves).isEqualTo(page.shelves)
        verifyBlocking(moods, times(1)) { page(any(), any()) }
    }

    @Test
    fun `switching to Offline mid-read stops it, and switching back reads again`() = runTest(dispatcher) {
        val gate = CompletableDeferred<MoodPage>()
        val slow = mock<MoodsRepository> { onBlocking { page(any(), any()) } doSuspendableAnswer { gate.await() } }
        val vm = viewModel(repo = slow, params = "p")
        runCurrent()
        assertThat(vm.state.value.loading).isTrue()

        streaming.value = false
        runCurrent()
        // The read was cancelled: an answer now changes nothing
        gate.complete(page)
        runCurrent()
        assertThat(vm.state.value.offline).isTrue()
        assertThat(vm.state.value.shelves).isEmpty()

        streaming.value = true
        runCurrent()
        assertThat(vm.state.value.shelves).isEqualTo(page.shelves)
        verifyBlocking(slow, times(2)) { page(any(), any()) }
    }

    @Test
    fun `a genre's song card plays its Songs row from that song, streamed, named for the genre`() = runTest(dispatcher) {
        val songs = (1..5).map { TrackSummary("v$it", "Song $it", "Artist $it", "Album $it", 200.0, "https://art/$it", musicVideoType = "ATV") }
        val rock = mock<MoodsRepository> { onBlocking { page(any(), any()) } doReturn MoodPage("Rock", listOf(MoodShelf("Songs", songs = songs))) }
        val vm = viewModel(repo = rock, params = "g", title = "Rock")
        runCurrent()
        vm.playSongs(songs, 3)
        runCurrent()
        val queue = argumentCaptor<List<Track>>()
        verifyBlocking(player) { setQueue(queue.capture(), eq(3), eq(PlaybackSource.Listening("Rock"))) }
        assertThat(queue.firstValue.map { it.youtubeId }).isEqualTo(songs.map { it.videoId })
        assertThat(queue.firstValue.all { it.source == MusicSource.YOUTUBE && it.isStreamable }).isTrue()
        assertThat(queue.firstValue[3].durationMs).isEqualTo(200_000L)
        assertThat(queue.firstValue[3].album).isEqualTo("Album 4")
    }
}
