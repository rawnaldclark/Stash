package com.stash.feature.search

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.data.ytmusic.model.MoodGrids
import com.stash.data.ytmusic.model.MoodTile
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * [MoodBrowseViewModel], Search's grid: the saved tiles and covers paint at once and today's follow; covers are read
 * only for the tiles on screen, once a visit; Offline, or with Settings' Moods & genres in Search off, there is no grid
 * and nothing is read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodBrowseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val chill = MoodTile("Chill", "p1", 1L)
    private val focus = MoodTile("Focus", "p2", 2L)
    private val rock = MoodTile("Rock", "g1", 3L)
    private val pop = MoodTile("Pop", "g2", 4L)
    private val saved = MoodGrids(listOf(chill), listOf(rock))
    private val today = MoodGrids(listOf(chill, focus), listOf(rock, pop))

    private val online = MutableStateFlow(true)
    private val switchOn = MutableStateFlow(true)
    private val moods = mock<MoodsRepository> {
        onBlocking { cached() } doReturn saved
        onBlocking { savedCovers() } doReturn mapOf("p1" to listOf("old1", "old2"))
        onBlocking { grids() } doReturn today
        onBlocking { covers(any(), any(), any()) } doSuspendableAnswer { call -> listOf("${call.arguments[0]}-cover") }
    }
    private val connectivity = mock<ConnectivityMonitor> { on { isConnected() } doReturn true }

    private fun viewModel() = MoodBrowseViewModel(
        moods,
        mock<StreamingPreference> { on { enabled } doReturn online },
        connectivity,
        mock<HomeSectionsPreference> { on { searchBrowseOn } doReturn switchOn },
    )

    @Test
    fun `Online, the saved tiles and covers paint first, then today's tiles`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.online).isTrue()
        assertThat(vm.state.value.loaded).isTrue()
        assertThat(vm.state.value.moods).isEqualTo(today.moods)
        assertThat(vm.state.value.genres).isEqualTo(today.genres)
        assertThat(vm.state.value.covers).containsEntry("p1", listOf("old1", "old2"))
        assertThat(vm.state.value.hasTiles).isTrue()
        verifyBlocking(moods, times(1)) { cached() }
        verifyBlocking(moods, times(1)) { grids() }
        // Nothing on screen yet: no cover is read
        verifyBlocking(moods, never()) { covers(any(), any(), any()) }
    }

    @Test
    fun `covers are read only for the tiles on screen, and once a visit`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.onTilesVisible(listOf(chill, focus))
        runCurrent()
        verifyBlocking(moods, times(1)) { covers(eq("p1"), eq("Chill"), any()) }
        verifyBlocking(moods, times(1)) { covers(eq("p2"), eq("Focus"), any()) }
        verifyBlocking(moods, never()) { covers(eq("g1"), any(), any()) }
        verifyBlocking(moods, never()) { covers(eq("g2"), any(), any()) }
        assertThat(vm.state.value.covers).containsEntry("p2", listOf("p2-cover"))
        // Scrolled to Genres: only the newcomers are asked for; Chill and Focus aren't asked again this visit
        vm.onTilesVisible(listOf(focus, rock, pop))
        vm.onTilesVisible(listOf(chill, focus))
        runCurrent()
        verifyBlocking(moods, times(1)) { covers(eq("g1"), eq("Rock"), any()) }
        verifyBlocking(moods, times(1)) { covers(eq("g2"), eq("Pop"), any()) }
        verifyBlocking(moods, times(1)) { covers(eq("p1"), any(), any()) }
        verifyBlocking(moods, times(1)) { covers(eq("p2"), any(), any()) }
    }

    @Test
    fun `the repository is told when a tile left the screen before its turn`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var stillWanted: (() -> Boolean)? = null
        whenever(moods.covers(eq("g1"), any(), any())).doSuspendableAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            stillWanted = call.arguments[2] as () -> Boolean
            gate.await()
            null
        }
        val vm = viewModel()
        runCurrent()
        vm.onTilesVisible(listOf(rock))
        runCurrent()
        assertThat(stillWanted!!()).isTrue()
        vm.onTilesVisible(listOf(chill))
        assertThat(stillWanted!!()).isFalse()
        gate.complete(Unit)
        runCurrent()
        // Scrolled away, not failed: back on screen it's asked for again at once
        vm.onTilesVisible(listOf(rock))
        runCurrent()
        verifyBlocking(moods, times(2)) { covers(eq("g1"), any(), any()) }
    }

    @Test
    fun `a read that failed or was held by Data saver waits for the next visit`() = runTest(dispatcher) {
        whenever(moods.covers(eq("p1"), any(), any())).doReturn(null)
        val vm = viewModel()
        runCurrent()
        vm.onTilesVisible(listOf(chill))
        runCurrent()
        // Still on screen: a scroll doesn't ask again
        vm.onTilesVisible(listOf(chill, focus))
        runCurrent()
        verifyBlocking(moods, times(1)) { covers(eq("p1"), any(), any()) }
        // Back to Search: asked once more
        vm.onResume()
        runCurrent()
        verifyBlocking(moods, times(2)) { covers(eq("p1"), any(), any()) }
    }

    @Test
    fun `with no network the saved tiles show and nothing is read`() = runTest(dispatcher) {
        whenever(connectivity.isConnected()).doReturn(false)
        val vm = viewModel()
        runCurrent()
        vm.onTilesVisible(listOf(chill, rock))
        runCurrent()
        assertThat(vm.state.value.moods).isEqualTo(saved.moods)
        assertThat(vm.state.value.genres).isEqualTo(saved.genres)
        verifyBlocking(moods, never()) { grids() }
        verifyBlocking(moods, never()) { covers(any(), any(), any()) }
    }

    @Test
    fun `Offline there is no grid and nothing is read, and a cover read under way stops`() = runTest(dispatcher) {
        val never = CompletableDeferred<List<String>?>()
        var cancelled = false
        whenever(moods.covers(eq("p1"), any(), any())).doSuspendableAnswer {
            try {
                never.await()
            } finally {
                cancelled = true
            }
        }
        val vm = viewModel()
        runCurrent()
        vm.onTilesVisible(listOf(chill))
        runCurrent()
        online.value = false
        runCurrent()
        assertThat(cancelled).isTrue()
        assertThat(vm.state.value).isEqualTo(MoodBrowseState(online = false))
        assertThat(vm.state.value.hasTiles).isFalse()
        // Offline, the screen's reports and resumes read nothing
        vm.onTilesVisible(listOf(focus, rock))
        vm.onResume()
        runCurrent()
        verifyBlocking(moods, never()) { covers(eq("p2"), anyOrNull(), any()) }
        verifyBlocking(moods, times(1)) { grids() }

        // Online again: the saved tiles paint and the tiles on screen are asked for
        online.value = true
        runCurrent()
        assertThat(vm.state.value.hasTiles).isTrue()
        verifyBlocking(moods, times(2)) { cached() }
        verifyBlocking(moods, times(1)) { covers(eq("p2"), any(), any()) }
    }

    @Test
    fun `Offline from the first look, there is no grid and no read at all`() = runTest(dispatcher) {
        online.value = false
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.online).isFalse()
        assertThat(vm.state.value.hasTiles).isFalse()
        verifyBlocking(moods, never()) { cached() }
        verifyBlocking(moods, never()) { grids() }
    }

    @Test
    fun `once the grid has left, a return to Search asks for no covers - only the grid's next report does`() = runTest(dispatcher) {
        whenever(moods.covers(eq("p1"), any(), any())).doReturn(null)
        val vm = viewModel()
        runCurrent()
        vm.onTilesVisible(listOf(chill))
        runCurrent()
        verifyBlocking(moods, times(1)) { covers(eq("p1"), any(), any()) }
        // The field tapped: the grid reports it's gone, and the app goes to the background and back
        vm.onTilesVisible(emptyList())
        vm.onResume()
        runCurrent()
        verifyBlocking(moods, times(1)) { covers(eq("p1"), any(), any()) }
        // The grid back on screen
        vm.onTilesVisible(listOf(chill))
        runCurrent()
        verifyBlocking(moods, times(2)) { covers(eq("p1"), any(), any()) }
    }

    @Test
    fun `saved tiles mean a grid at once, before today's read answers - and Offline means none`() = runTest(dispatcher) {
        val gate = CompletableDeferred<MoodGrids>()
        whenever(moods.grids()).doSuspendableAnswer { gate.await() }
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.showsGrid).isTrue()
        online.value = false
        runCurrent()
        assertThat(vm.state.value.showsGrid).isFalse()
    }

    @Test
    fun `with nothing saved, Search waits for today's read to know whether it has a grid`() = runTest(dispatcher) {
        val gate = CompletableDeferred<MoodGrids>()
        whenever(moods.cached()).doReturn(MoodGrids.None)
        whenever(moods.grids()).doSuspendableAnswer { gate.await() }
        val vm = viewModel()
        runCurrent()
        // A fresh install with network: neither the grid nor "no grid" (which would raise the keyboard) yet
        assertThat(vm.state.value.showsGrid).isNull()
        gate.complete(today)
        runCurrent()
        assertThat(vm.state.value.showsGrid).isTrue()
    }

    @Test
    fun `with nothing saved and no tiles to be had, Search knows it has no grid`() = runTest(dispatcher) {
        whenever(moods.cached()).doReturn(MoodGrids.None)
        // YouTube answering without them
        whenever(moods.grids()).doReturn(MoodGrids.None)
        val unanswered = viewModel()
        runCurrent()
        assertThat(unanswered.state.value.showsGrid).isFalse()
        // The read failing outright
        whenever(moods.grids()).doSuspendableAnswer { throw IllegalStateException("down") }
        val failed = viewModel()
        runCurrent()
        assertThat(failed.state.value.showsGrid).isFalse()
        // No network to try
        whenever(connectivity.isConnected()).doReturn(false)
        val unconnected = viewModel()
        runCurrent()
        assertThat(unconnected.state.value.showsGrid).isFalse()
    }

    @Test
    fun `switched off in Settings, Search is as it was before the grid - known at once, and nothing is read`() = runTest(dispatcher) {
        switchOn.value = false
        val vm = viewModel()
        runCurrent()
        // False, not null: the screen shows the recent searches and focuses the field on the first open, as before #823
        assertThat(vm.state.value.showsGrid).isFalse()
        assertThat(vm.state.value.hasTiles).isFalse()
        assertThat(vm.state.value).isEqualTo(MoodBrowseState(online = true, switchedOff = true))
        // The screen's reports and resumes read nothing either: not the day's grids, not a cover, not the saved files
        vm.onTilesVisible(listOf(chill, rock))
        vm.onResume()
        runCurrent()
        verifyBlocking(moods, never()) { cached() }
        verifyBlocking(moods, never()) { savedCovers() }
        verifyBlocking(moods, never()) { grids() }
        verifyBlocking(moods, never()) { covers(any(), any(), any()) }
    }

    @Test
    fun `switched off while showing, the grid goes and a cover read stops - on again, it comes back`() = runTest(dispatcher) {
        val never = CompletableDeferred<List<String>?>()
        var cancelled = false
        whenever(moods.covers(eq("p1"), any(), any())).doSuspendableAnswer {
            try {
                never.await()
            } finally {
                cancelled = true
            }
        }
        val vm = viewModel()
        runCurrent()
        vm.onTilesVisible(listOf(chill))
        runCurrent()
        assertThat(vm.state.value.showsGrid).isTrue()

        switchOn.value = false
        runCurrent()
        assertThat(cancelled).isTrue()
        assertThat(vm.state.value.showsGrid).isFalse()
        vm.onTilesVisible(listOf(focus))
        vm.onResume()
        runCurrent()
        verifyBlocking(moods, never()) { covers(eq("p2"), anyOrNull(), any()) }
        verifyBlocking(moods, times(1)) { grids() }

        // On again (Online all along): the saved tiles paint, today's are read, and the tiles on screen get covers
        switchOn.value = true
        runCurrent()
        assertThat(vm.state.value.showsGrid).isTrue()
        verifyBlocking(moods, times(2)) { cached() }
        verifyBlocking(moods, times(2)) { grids() }
        verifyBlocking(moods, times(1)) { covers(eq("p2"), any(), any()) }
    }

    @Test
    fun `Offline with the switch off is still no grid, and the switch on again Offline reads nothing`() = runTest(dispatcher) {
        switchOn.value = false
        online.value = false
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.showsGrid).isFalse()
        switchOn.value = true
        runCurrent()
        assertThat(vm.state.value).isEqualTo(MoodBrowseState(online = false))
        verifyBlocking(moods, never()) { cached() }
        verifyBlocking(moods, never()) { grids() }
    }
}
