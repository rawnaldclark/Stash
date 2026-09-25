package com.stash.feature.nowplaying.listen

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinSessionViewModelTest {
    private val api: RoomApiClient = mockk()
    private val controller: ListenTogetherController = mockk(relaxed = true)
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
        coEvery { api.preview(any()) } returns ShareResult.Failed("offline")
        assertEquals(JoinSessionViewModel.UiState.Failed, vm().state.value)
    }

    @Test fun `join saves the name, asks the session to join, then moves on`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 1, false))
        val vm = vm()
        vm.onNameChange("Sam")
        var joined = false
        vm.join { joined = true }
        coVerifyOrder {
            sharePreference.setDisplayName("Sam")
            controller.send(ListenTogetherController.Command.Join("K7QA2PXM"))
        }
        assertTrue(joined)
    }
}
