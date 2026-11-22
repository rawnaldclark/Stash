package com.stash.feature.community.sessions

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.model.community.FollowState
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationLive
import com.stash.core.model.community.StationPage
import com.stash.feature.community.sessions.StationViewModel.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<StationsRepository>(relaxed = true)
    private val maya = Station(id = "Ab3dEf9h", name = "Maya's Desk Radio", hostName = "Maya", vibe = "late-night r&b", followers = 38, up = 31, down = 8)
    private val live = StationLive(room = "ROOM0001", listeners = 14)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() { Dispatchers.resetMain() }

    /** StationRoute(stationId): type-safe navigation stores it under the property name. */
    private fun vm() = StationViewModel(SavedStateHandle(mapOf("stationId" to "Ab3dEf9h")), repo)

    @Test fun `a station on the air shows with its session, and one that isn't shows without`() = runTest(dispatcher) {
        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Ok(StationPage(maya, live))
        val on = vm(); advanceUntilIdle()
        assertThat(on.state.value).isEqualTo(UiState.Loaded(maya, live))
        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Ok(StationPage(maya))
        val off = vm(); advanceUntilIdle()
        assertThat(off.state.value).isEqualTo(UiState.Loaded(maya, null))
    }

    @Test fun `gone, hidden, or Sessions off all read as not available`() = runTest(dispatcher) {
        for (code in listOf("gone", CommunityResult.SESSIONS_OFF, "off", "not_found")) {
            coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Rejected(code)
            val vm = vm(); advanceUntilIdle()
            assertThat(vm.state.value).isEqualTo(UiState.Unavailable)
        }
    }

    @Test fun `a station that couldn't be reached offers a retry`() = runTest(dispatcher) {
        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Failed("offline")
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Failed)
        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Ok(StationPage(maya))
        vm.load(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Loaded(maya, null))
    }

    @Test fun `Follow shows at once, on the button and the count, then takes the server's`() = runTest(dispatcher) {
        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Ok(StationPage(maya))
        val answer = CompletableDeferred<CommunityResult<FollowState>>()
        coEvery { repo.follow("Ab3dEf9h", true) } coAnswers { answer.await() }
        val vm = vm(); advanceUntilIdle()
        vm.follow(); runCurrent()
        assertThat(vm.state.value).isEqualTo(UiState.Loaded(maya.copy(following = true, followers = 39), null))
        answer.complete(CommunityResult.Ok(FollowState(following = true, followers = 41))); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Loaded(maya.copy(following = true, followers = 41), null))
    }

    @Test fun `a follow that fails rolls back and says why`() = runTest(dispatcher) {
        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Ok(StationPage(maya.copy(following = true)))
        coEvery { repo.follow("Ab3dEf9h", false) } returns CommunityResult.Failed("offline")
        val vm = vm(); advanceUntilIdle()
        vm.follow(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Loaded(maya.copy(following = true), null))
        assertThat(vm.message.value).isEqualTo("Couldn't reach Community. Try again.")
    }

    @Test fun `your own station has no Follow, and a second tap waits for the first`() = runTest(dispatcher) {
        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Ok(StationPage(maya.copy(mine = true)))
        val own = vm(); advanceUntilIdle()
        own.follow(); advanceUntilIdle()
        coVerify(exactly = 0) { repo.follow(any(), any()) }

        coEvery { repo.station("Ab3dEf9h") } returns CommunityResult.Ok(StationPage(maya))
        val answer = CompletableDeferred<CommunityResult<FollowState>>()
        coEvery { repo.follow("Ab3dEf9h", true) } coAnswers { answer.await() }
        val vm = vm(); advanceUntilIdle()
        vm.follow(); vm.follow(); runCurrent()
        coVerify(exactly = 1) { repo.follow(any(), any()) }
    }
}
