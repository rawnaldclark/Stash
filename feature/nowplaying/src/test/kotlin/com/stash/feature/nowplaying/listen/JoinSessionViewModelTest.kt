package com.stash.feature.nowplaying.listen

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinSessionViewModelTest {
    private val api: RoomApiClient = mockk()
    private val roomState = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    private val controller: ListenTogetherController = mockk(relaxed = true) { every { state } returns roomState }
    private fun inRoom(code: String) = ListenTogetherState.InRoom(
        code = code, url = "https://x/l/$code", myId = "me", isHost = false, hostId = "h", members = emptyList(), suggestions = emptyList(),
    )
    private val sharePreference: SharePreference = mockk(relaxed = true) { coEvery { displayName() } returns null }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = JoinSessionViewModel(SavedStateHandle(mapOf("code" to "K7QA2PXM")), api, controller, sharePreference)

    @Test fun `an open room shows the host, the count and the song`() {
        coEvery { api.preview("K7QA2PXM") } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 3, false, SharedTrack("Xtal", "Aphex Twin")))
        assertEquals(JoinSessionViewModel.UiState.Ready("Rawn", 3, SharedTrack("Xtal", "Aphex Twin")), vm().state.value)
    }

    @Test fun `full, ended and unreachable rooms each say so`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 10, true))
        assertEquals(JoinSessionViewModel.UiState.Full, vm().state.value)
        coEvery { api.preview(any()) } returns ShareResult.NotFound
        assertEquals(JoinSessionViewModel.UiState.Ended, vm().state.value)
        coEvery { api.preview(any()) } returns ShareResult.Gone
        assertEquals(JoinSessionViewModel.UiState.Ended, vm().state.value)
        coEvery { api.preview(any()) } returns ShareResult.Failed("offline")
        assertEquals(JoinSessionViewModel.UiState.Failed, vm().state.value)
        coEvery { api.preview(any()) } returns ShareResult.Failed(ShareResult.Failed.RATE_LIMITED)
        assertEquals(JoinSessionViewModel.UiState.RateLimited, vm().state.value)
    }

    @Test fun `a second Join tap does nothing while the first is under way`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 1, false))
        val vm = vm()
        var joined = 0
        vm.join { joined++ }
        vm.join { joined++ }
        assertTrue(vm.joining)
        roomState.value = ListenTogetherState.Connecting(hosting = false)
        roomState.value = inRoom("K7QA2PXM")
        assertEquals(1, joined)
        coVerify(exactly = 1) { controller.send(ListenTogetherController.Command.Join("K7QA2PXM")) }
    }

    @Test fun `a saved name never overwrites one already typed`() {
        val saved = CompletableDeferred<String?>()
        coEvery { sharePreference.displayName() } coAnswers { saved.await() }
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 1, false))
        val vm = vm()
        vm.onNameChange("Sam")
        saved.complete("Old name")
        assertEquals("Sam", vm.name)
    }

    @Test fun `join saves the name, asks the session to join, and moves on only once in the room`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 1, false))
        val vm = vm()
        vm.onNameChange("Sam")
        var joined = false
        vm.join { joined = true }
        coVerifyOrder {
            sharePreference.setDisplayName("Sam")
            controller.send(ListenTogetherController.Command.Join("K7QA2PXM"))
        }
        roomState.value = ListenTogetherState.Connecting(hosting = false)
        assertFalse(joined) // still here, showing progress
        roomState.value = inRoom("K7QA2PXM")
        assertTrue(joined)
    }

    @Test fun `a join that fails stays here, stops the spinner and checks the room again`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 1, false))
        val vm = vm()
        var joined = false
        vm.join { joined = true }
        roomState.value = ListenTogetherState.Connecting(hosting = false)
        coEvery { api.preview(any()) } returns ShareResult.Gone
        roomState.value = ListenTogetherState.Idle
        assertFalse(joined)
        assertFalse(vm.joining)
        assertEquals(JoinSessionViewModel.UiState.Ended, vm.state.value)
    }

    @Test fun `an invite for the room you're already in just opens it`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 2, false))
        roomState.value = inRoom("K7QA2PXM")
        val vm = vm()
        assertTrue(vm.alreadyHere)
        var joined = false
        vm.join { joined = true }
        assertTrue(joined)
        coVerify(exactly = 0) { controller.send(any()) }
    }

    @Test fun `leaving another room on the way doesn't count as a failed join`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 1, false))
        roomState.value = inRoom("OLDROOM1")
        val vm = vm()
        var joined = false
        vm.join { joined = true }
        roomState.value = ListenTogetherState.Idle // the old room closing
        assertTrue(vm.joining)
        roomState.value = ListenTogetherState.Connecting(hosting = false)
        roomState.value = inRoom("K7QA2PXM")
        assertTrue(joined)
    }
}
