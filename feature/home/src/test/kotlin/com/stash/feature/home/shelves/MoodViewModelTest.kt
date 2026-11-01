package com.stash.feature.home.shelves

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodPlaylist
import com.stash.data.ytmusic.model.MoodShelf
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking

/**
 * [MoodViewModel]: a mood's page reads YouTube only while Online. Offline (reachable from the back stack after the
 * switch) it reads nothing and says so, and Online opens it.
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

    private fun viewModel() = MoodViewModel(
        SavedStateHandle(mapOf(MoodViewModel.ARG_PARAMS to "ggMPOg1uX1JOQWZFeDByc2Jm", MoodViewModel.ARG_TITLE to "Chill")),
        moods,
        mock<StreamingPreference> { on { enabled } doReturn streaming },
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
        val vm = MoodViewModel(
            SavedStateHandle(mapOf(MoodViewModel.ARG_PARAMS to "p", MoodViewModel.ARG_TITLE to "Chill")),
            slow,
            mock<StreamingPreference> { on { enabled } doReturn streaming },
        )
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
}
