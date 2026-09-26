package com.stash.feature.nowplaying.listen

import com.stash.core.data.share.SharePreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the name field starts from the saved Show my name as`() {
        assertEquals("Rawn", ListenTogetherViewModel(controller, sharePreference, playerRepository).name)
    }

    @Test fun `starting saves the name, loads a restored-only queue, then asks for a room`() {
        val vm = ListenTogetherViewModel(controller, sharePreference, playerRepository)
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
        val vm = ListenTogetherViewModel(controller, sharePreference, playerRepository)
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
}
