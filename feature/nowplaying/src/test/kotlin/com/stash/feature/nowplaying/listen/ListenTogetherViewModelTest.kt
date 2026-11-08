package com.stash.feature.nowplaying.listen

import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.share.SharePreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherViewModelTest {
    private val controller: ListenTogetherController = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(ListenTogetherState.Idle)
    }
    private val sharePreference: SharePreference = mockk(relaxed = true) { coEvery { displayName() } returns "Rawn" }
    private val playerRepository: PlayerRepository = mockk(relaxed = true)

    /** The app's one mode switch: `false` is Offline. Starts Offline so a view model that never reads it fails. */
    private val streamingOn = MutableStateFlow(false)
    private val streamingPreference: StreamingPreference = mockk { every { enabled } returns streamingOn }

    private fun viewModel() = ListenTogetherViewModel(controller, sharePreference, playerRepository, streamingPreference)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the name field starts from the saved Show my name as`() {
        assertEquals("Rawn", viewModel().name)
    }

    @Test fun `starting saves the name, loads a restored-only queue, then asks for a room`() {
        val vm = viewModel()
        vm.onNameChange("Sam")
        vm.start()
        // Device test 2026-09-25: a session started from the cold-start screen had no song in the room.
        coVerifyOrder {
            sharePreference.setDisplayName("Sam")
            playerRepository.loadRestoredQueue()
            controller.send(Command.Host)
        }
    }

    @Test fun `leave, end, react, make host, queue edits and rejoin go straight to the controller`() {
        val vm = viewModel()
        val song = SharedTrack("Nude", "Radiohead")
        vm.leave(); vm.end(); vm.react("x"); vm.makeHost("m2"); vm.answerSuggestion("s1", add = true); vm.editQueue(listOf(song)); vm.rejoin()
        verify { controller.send(Command.Leave) }
        verify { controller.send(Command.End) }
        verify { controller.send(Command.React("x")) }
        verify { controller.send(Command.MakeHost("m2")) }
        verify { controller.send(Command.Suggestion("s1", true)) }
        verify { controller.send(Command.SetQueue(listOf(song))) }
        verify { controller.rejoin() }
    }

    @Test fun `a guest's own song goes out through the controller`() {
        val vm = viewModel()
        val mine = SharedTrack("Nude", "Radiohead", addedBy = "me")
        vm.unqueue(mine, 2)
        verify { controller.send(Command.Unqueue(mine, 2)) }
    }

    @Test fun `the host's switch reaches the room and is remembered for the next session`() {
        val vm = viewModel()
        vm.setGuestsAdd(false)
        verify { controller.send(Command.SetGuestsAdd(false)) }
        coVerify { sharePreference.setGuestsAdd(false) }
    }

    @Test fun `the Offline hint reads the phone's real mode and follows a change while it's on screen`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.offlineMode.collect {} }
        assertEquals("the first read, not the placeholder", true, vm.offlineMode.value)
        streamingOn.value = true
        assertEquals("switched to Online with the sheet open", false, vm.offlineMode.value)
        streamingOn.value = false
        assertEquals("and back to Offline", true, vm.offlineMode.value)
    }
}
