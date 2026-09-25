package com.stash.feature.nowplaying.listen

import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
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

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the name field starts from the saved Show my name as`() {
        assertEquals("Rawn", ListenTogetherViewModel(controller, sharePreference).name)
    }

    @Test fun `starting saves the name, then asks for a room`() {
        val vm = ListenTogetherViewModel(controller, sharePreference)
        vm.onNameChange("Sam")
        vm.start()
        coVerifyOrder {
            sharePreference.setDisplayName("Sam")
            controller.send(Command.Host)
        }
    }

    @Test fun `leave, end, react and make host go straight to the controller`() {
        val vm = ListenTogetherViewModel(controller, sharePreference)
        vm.leave(); vm.end(); vm.react("x"); vm.makeHost("m2"); vm.answerSuggestion("s1", add = true)
        verify { controller.send(Command.Leave) }
        verify { controller.send(Command.End) }
        verify { controller.send(Command.React("x")) }
        verify { controller.send(Command.MakeHost("m2")) }
        verify { controller.send(Command.Suggestion("s1", true)) }
    }
}
