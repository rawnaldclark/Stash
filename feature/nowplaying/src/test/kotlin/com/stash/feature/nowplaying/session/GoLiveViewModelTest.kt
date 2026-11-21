package com.stash.feature.nowplaying.session

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationEdit
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.community.Station
import com.stash.core.model.listen.SessionInfo
import com.stash.feature.nowplaying.session.GoLiveViewModel.Note
import com.stash.feature.nowplaying.session.GoLiveViewModel.Step
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoLiveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<StationsRepository>(relaxed = true)
    private val homeSections = mockk<HomeSectionsPreference>()
    private val share = mockk<SharePreference>(relaxed = true)
    private val controller = mockk<ListenTogetherController>(relaxed = true)

    private val communityOn = MutableStateFlow(true)
    private val sessionsOn = MutableStateFlow<Boolean?>(true)
    private val room = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    private val own = Station(id = "Ab3dEf9h", name = "Rawn's Basement", hostName = "Rawn", vibe = "90s house", mine = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { homeSections.communityOn } returns communityOn
        every { repo.sessionsOn } returns sessionsOn
        every { controller.state } returns room
        coEvery { share.displayName() } returns "Rawn"
        coEvery { repo.mine() } returns CommunityResult.Ok(null)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = GoLiveViewModel(repo, homeSections, share, controller)

    private fun onAir(stationId: String = "Ab3dEf9h", listeners: Int = 0) = ListenTogetherState.InRoom(
        code = "MYROOM01", url = "https://stashfm.app/s/$stationId", myId = "ME000001", isHost = true, hostId = "ME000001",
        members = emptyList(), suggestions = emptyList(), session = SessionInfo(station = stationId, listeners = listeners), role = SessionRole.HOST,
    )

    /**
     * The engine's side of going live: Connecting, then the room. A go-live waits up to 20 s for it, so these tests
     * step with runCurrent where one is on its way: advanceUntilIdle would run the clock past the wait.
     */
    private fun engineGoesLive(stationId: String = "Ab3dEf9h") {
        room.value = ListenTogetherState.Connecting(hosting = true, station = stationId)
        dispatcher.scheduler.runCurrent()
        room.value = onAir(stationId)
        dispatcher.scheduler.runCurrent()
    }

    // ── Whether Go live is offered at all ───────────────────────────────────────

    @Test fun `it's offered only while Community is on and the server says Sessions is`() = runTest(dispatcher) {
        sessionsOn.value = null
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value.available).isFalse()
        // Before anything else has asked, this asks once.
        coVerify(exactly = 1) { repo.ensureKnown() }
        sessionsOn.value = true; advanceUntilIdle()
        assertThat(vm.state.value.available).isTrue()
        communityOn.value = false; advanceUntilIdle()
        assertThat(vm.state.value.available).isFalse()
        sessionsOn.value = false; communityOn.value = true; advanceUntilIdle()
        assertThat(vm.state.value.available).isFalse()
    }

    @Test fun `with Community off, the server isn't asked`() = runTest(dispatcher) {
        communityOn.value = false
        sessionsOn.value = null
        vm(); advanceUntilIdle()
        coVerify(exactly = 0) { repo.ensureKnown() }
    }

    // ── The first time: no station yet ──────────────────────────────────────────

    @Test fun `with no station, Go live opens the sheet, and its Go live makes the station, then goes live`() = runTest(dispatcher) {
        coEvery { repo.create(StationEdit(name = "Rawn's Basement", hostName = "Rawn", vibe = "90s house")) } returns CommunityResult.Ok("Ab3dEf9h")
        val vm = vm(); advanceUntilIdle()
        var opened = 0
        vm.begin { opened++ }; advanceUntilIdle()
        val form = vm.state.value.step as Step.Form
        assertThat(form.station).isNull()
        assertThat(form.hostName).isEqualTo("Rawn")
        verify(exactly = 0) { controller.send(any()) }

        vm.submit(name = " Rawn's Basement ", hostName = "Rawn", vibe = "90s house"); runCurrent()
        coVerifyOrder {
            repo.create(StationEdit(name = "Rawn's Basement", hostName = "Rawn", vibe = "90s house"))
            controller.send(Command.GoLive("Ab3dEf9h", "Rawn"))
        }
        assertThat(vm.state.value.step).isEqualTo(Step.Going)
        engineGoesLive(); advanceUntilIdle()
        assertThat(opened).isEqualTo(1)
        assertThat(vm.state.value.step).isEqualTo(Step.Idle)
    }

    @Test fun `a new name for yourself is kept as your display name`() = runTest(dispatcher) {
        coEvery { repo.create(any()) } returns CommunityResult.Ok("Ab3dEf9h")
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; advanceUntilIdle()
        vm.submit(name = "Rawn's Basement", hostName = "DJ Rawn", vibe = ""); runCurrent()
        coVerify { share.setDisplayName("DJ Rawn") }
        // No vibe line: none is sent.
        coVerify { repo.create(StationEdit(name = "Rawn's Basement", hostName = "DJ Rawn", vibe = null)) }
        verify { controller.send(Command.GoLive("Ab3dEf9h", "DJ Rawn")) }
    }

    @Test fun `a station needs a name`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; advanceUntilIdle()
        vm.submit(name = "   ", hostName = "Rawn", vibe = ""); advanceUntilIdle()
        coVerify(exactly = 0) { repo.create(any()) }
        assertThat(vm.state.value.step).isInstanceOf(Step.Form::class.java)
    }

    @Test fun `have_station, blocked and sessions_off each say why, and the sheet stays`() = runTest(dispatcher) {
        val says = mapOf(
            "have_station" to "You already have a station.",
            "blocked" to "You can't do that in Community.",
            CommunityResult.SESSIONS_OFF to "Sessions isn't available right now.",
        )
        for ((code, sentence) in says) {
            coEvery { repo.create(any()) } returns CommunityResult.Rejected(code)
            val vm = vm(); advanceUntilIdle()
            vm.begin {}; advanceUntilIdle()
            vm.submit(name = "Rawn's Basement", hostName = "Rawn", vibe = ""); advanceUntilIdle()
            assertThat(vm.state.value.message).isEqualTo(sentence)
            assertThat((vm.state.value.step as Step.Form).sending).isFalse()
        }
        verify(exactly = 0) { controller.send(any()) }
    }

    // ── After that: one tap ─────────────────────────────────────────────────────

    @Test fun `with a station, Go live goes live at once`() = runTest(dispatcher) {
        coEvery { repo.mine() } returns CommunityResult.Ok(own)
        val vm = vm(); advanceUntilIdle()
        var opened = 0
        vm.begin { opened++ }; runCurrent()
        verify(exactly = 1) { controller.send(Command.GoLive("Ab3dEf9h", "Rawn")) }
        assertThat(vm.state.value.step).isEqualTo(Step.Going)
        coVerify(exactly = 0) { repo.create(any()) }
        engineGoesLive(); advanceUntilIdle()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun `a second tap while going live does nothing more`() = runTest(dispatcher) {
        coEvery { repo.mine() } returns CommunityResult.Ok(own)
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; vm.begin {}; runCurrent()
        vm.begin {}; runCurrent()
        verify(exactly = 1) { controller.send(any()) }
    }

    @Test fun `already on the air, Go live just opens the session`() = runTest(dispatcher) {
        room.value = onAir()
        val vm = vm(); advanceUntilIdle()
        var opened = 0
        vm.begin { opened++ }; advanceUntilIdle()
        assertThat(opened).isEqualTo(1)
        coVerify(exactly = 0) { repo.mine() }
        verify(exactly = 0) { controller.send(any()) }
    }

    @Test fun `in a private room or someone else's session, it says what to do first`() = runTest(dispatcher) {
        val privateRoom = onAir().copy(session = null, role = null)
        room.value = privateRoom
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; advanceUntilIdle()
        assertThat(vm.state.value.note).isEqualTo(Note.END_PRIVATE_FIRST)
        vm.noteShown()
        room.value = onAir("OTHER001").copy(isHost = false, hostId = "HOST0001", role = SessionRole.LISTENER)
        vm.begin {}; advanceUntilIdle()
        assertThat(vm.state.value.note).isEqualTo(Note.LEAVE_FIRST)
        verify(exactly = 0) { controller.send(any()) }
        coVerify(exactly = 0) { repo.mine() }
    }

    @Test fun `a station that can't be read says why and opens nothing`() = runTest(dispatcher) {
        coEvery { repo.mine() } returns CommunityResult.Failed("offline")
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(Step.Idle)
        assertThat(vm.state.value.message).isEqualTo("Couldn't reach Community. Try again.")
    }

    // ── A go-live the server refuses ────────────────────────────────────────────

    @Test fun `a refused go-live from the sheet comes back to it, and a changed vibe line is saved first next time`() = runTest(dispatcher) {
        coEvery { repo.create(any()) } returns CommunityResult.Ok("Ab3dEf9h")
        coEvery { repo.update("Ab3dEf9h", StationEdit(vibe = "slow jams")) } returns CommunityResult.Ok(own.copy(vibe = "slow jams"))
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; advanceUntilIdle()
        vm.submit(name = "Rawn's Basement", hostName = "Rawn", vibe = "90s house"); runCurrent()
        // The engine couldn't open the session (it says why in its own toast): Connecting, then Idle.
        room.value = ListenTogetherState.Connecting(hosting = true, station = "Ab3dEf9h"); runCurrent()
        room.value = ListenTogetherState.Idle; advanceUntilIdle()
        val form = vm.state.value.step as Step.Form
        // The station was made: the sheet now holds it, so Go live doesn't make a second one.
        assertThat(form.station?.id).isEqualTo("Ab3dEf9h")
        assertThat(form.sending).isFalse()

        vm.submit(name = "Rawn's Basement", hostName = "Rawn", vibe = "slow jams"); runCurrent()
        coVerify(exactly = 1) { repo.create(any()) }
        coVerifyOrder {
            repo.update("Ab3dEf9h", StationEdit(vibe = "slow jams"))
            controller.send(Command.GoLive("Ab3dEf9h", "Rawn"))
        }
    }

    @Test fun `an edit the server refuses says why and doesn't go live`() = runTest(dispatcher) {
        coEvery { repo.create(any()) } returns CommunityResult.Ok("Ab3dEf9h")
        coEvery { repo.update(any(), any()) } returns CommunityResult.Rejected("bad_request")
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; advanceUntilIdle()
        vm.submit(name = "Rawn's Basement", hostName = "Rawn", vibe = ""); runCurrent()
        room.value = ListenTogetherState.Connecting(hosting = true, station = "Ab3dEf9h"); runCurrent()
        room.value = ListenTogetherState.Idle; advanceUntilIdle()
        vm.submit(name = "Rawn's Attic", hostName = "Rawn", vibe = ""); advanceUntilIdle()
        assertThat(vm.state.value.message).isEqualTo("That can't be used. Try something else.")
        verify(exactly = 1) { controller.send(any()) }
    }

    @Test fun `a go-live that never answers stops waiting after 20 seconds`() = runTest(dispatcher) {
        coEvery { repo.mine() } returns CommunityResult.Ok(own)
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; runCurrent()
        advanceTimeBy(19_000); runCurrent()
        assertThat(vm.state.value.step).isEqualTo(Step.Going)
        advanceTimeBy(1_100); advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(Step.Idle)
    }

    @Test fun `Not now closes the sheet`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        vm.begin {}; advanceUntilIdle()
        vm.dismiss()
        assertThat(vm.state.value.step).isEqualTo(Step.Idle)
    }
}
