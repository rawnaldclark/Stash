package com.stash.feature.nowplaying

import android.content.Context
import app.cash.turbine.test
import com.stash.core.data.prefs.NowPlayingAction
import com.stash.core.data.prefs.NowPlayingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The ⋮ menu's pins and the lyrics mode switch, as the ViewModel holds them. */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingPinsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val pinsFlow = MutableStateFlow(NowPlayingAction.DefaultPins)
    private val preference: NowPlayingPreference = mockk(relaxed = true) {
        every { pinnedActions } returns pinsFlow
    }

    private fun newViewModel(): NowPlayingViewModel = NowPlayingViewModel(
        playerRepository = mockk<PlayerRepository>(relaxed = true) {
            every { playerState } returns MutableStateFlow(PlayerState())
            every { currentPosition } returns MutableStateFlow(0L)
        },
        musicRepository = mockk<MusicRepository>(relaxed = true) {
            every { observeTrackById(any()) } returns flowOf(null)
            every { getUserCreatedPlaylists() } returns flowOf(emptyList())
        },
        likeCoordinator = mockk<LikeCoordinator>(relaxed = true) {
            every { mirrorFailures } returns MutableSharedFlow()
        },
        losslessUpgrader = mockk(relaxed = true),
        lyricsRepository = mockk(relaxed = true),
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = preference,
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = mockk<Context>(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        ytMusicApiClient = mockk(relaxed = true),
    )

    @Test fun `pins load from the preference, null until then`() = runTest(dispatcher) {
        val vm = newViewModel()
        assertEquals(null, vm.pinnedActions.value)
        vm.pinnedActions.test {
            assertEquals(NowPlayingAction.DefaultPins, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `toggling a pin persists the new set`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.pinnedActions.test {
            awaitItem()
            assertTrue(vm.onTogglePin(NowPlayingAction.SHARE))
            coVerify { preference.setPinnedActions(setOf(NowPlayingAction.RADIO, NowPlayingAction.QUEUE, NowPlayingAction.SHARE)) }
            assertTrue(vm.onTogglePin(NowPlayingAction.RADIO))
            coVerify { preference.setPinnedActions(setOf(NowPlayingAction.QUEUE)) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a full row refuses another pin and writes nothing`() = runTest(dispatcher) {
        pinsFlow.value = NowPlayingAction.entries.take(NowPlayingAction.MAX_PINS).toSet()
        val vm = newViewModel()
        vm.pinnedActions.test {
            awaitItem()
            assertFalse(vm.onTogglePin(NowPlayingAction.FLAG_WRONG_MATCH))
            coVerify(exactly = 0) { preference.setPinnedActions(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a toggle before the pins load is ignored`() = runTest(dispatcher) {
        val vm = newViewModel()
        assertFalse(vm.onTogglePin(NowPlayingAction.SHARE))
        coVerify(exactly = 0) { preference.setPinnedActions(any()) }
    }

    @Test fun `reset clears the saved pins`() = runTest(dispatcher) {
        newViewModel().onResetPins()
        coVerify { preference.resetPinnedActions() }
    }

    @Test fun `lyrics mode opens, toggles and closes`() = runTest(dispatcher) {
        val vm = newViewModel()
        assertFalse(vm.lyricsMode.value)
        vm.onShowLyrics()
        assertTrue(vm.lyricsMode.value)
        vm.onToggleLyrics()
        assertFalse(vm.lyricsMode.value)
        vm.onToggleLyrics()
        assertTrue(vm.lyricsMode.value)
        vm.onDismissLyrics()
        assertFalse(vm.lyricsMode.value)
    }
}
