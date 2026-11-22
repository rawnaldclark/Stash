package com.stash.feature.community.sessions

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationLive
import com.stash.core.model.listen.SessionInfo
import io.mockk.coEvery
import io.mockk.coVerify
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
class SessionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<StationsRepository>(relaxed = true)
    private val homeSections = mockk<HomeSectionsPreference>()
    private val controller = mockk<ListenTogetherController>(relaxed = true)

    private val communityOn = MutableStateFlow(true)
    private val sessionsOn = MutableStateFlow<Boolean?>(null)
    private val revision = MutableStateFlow(0)
    private val room = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    private var now = 1_000_000L

    private fun station(id: String, following: Boolean = false, live: StationLive? = null) =
        Station(id = id, name = "Station $id", hostName = "Maya", following = following, live = live)

    private fun session(id: String, listeners: Int = 3, full: Boolean = false) =
        LiveSession(station = station(id), room = "ROOM$id".padEnd(8, 'X').take(8), listeners = listeners, full = full)

    /** The Worker answers as one with Sessions on does: every list call teaches the repository so. */
    private fun serverOn(live: List<LiveSession> = emptyList(), following: List<Station> = emptyList(), mine: Station? = null) {
        coEvery { repo.live(any()) } coAnswers { sessionsOn.value = true; CommunityResult.Ok(live) }
        coEvery { repo.following() } returns CommunityResult.Ok(following)
        coEvery { repo.mine() } returns CommunityResult.Ok(mine)
    }

    private val share = mockk<SharePreference>(relaxed = true)

    private fun vm() = SessionsViewModel(repo, homeSections, controller, share).also { it.clock = { now } }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { homeSections.communityOn } returns communityOn
        every { repo.sessionsOn } returns sessionsOn
        every { repo.revision } returns revision
        every { controller.state } returns room
        // Past its first tune-in, unless a test says otherwise.
        coEvery { share.tuneInNameSeen() } returns true
        coEvery { share.displayName() } returns "Rawn"
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `nothing loads and nothing shows while Community is off`() = runTest(dispatcher) {
        communityOn.value = false
        serverOn(live = listOf(session("AAAAAAAA")))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SessionsViewModel.UiState(off = true))
        coVerify(exactly = 0) { repo.live(any()) }
        coVerify(exactly = 0) { repo.following() }
    }

    @Test fun `a server with Sessions off is asked once, and nothing shows`() = runTest(dispatcher) {
        coEvery { repo.live(any()) } coAnswers { sessionsOn.value = false; CommunityResult.Rejected(CommunityResult.SESSIONS_OFF) }
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        now += 60_000
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SessionsViewModel.UiState(off = true))
        coVerify(exactly = 1) { repo.live(any()) }
        coVerify(exactly = 0) { repo.following() }
    }

    @Test fun `offline before the server has said, nothing shows, and the next visit asks again`() = runTest(dispatcher) {
        coEvery { repo.live(any()) } returns CommunityResult.Failed("offline")
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.on).isFalse()
        assertThat(vm.state.value.off).isFalse()
        assertThat(vm.state.value.live).isNull()
        // The Sessions screen offers a retry; Home shows nothing either way.
        assertThat(vm.state.value.failed).isTrue()
        serverOn(live = listOf(session("AAAAAAAA")))
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.on).isTrue()
        assertThat(vm.state.value.live).hasSize(1)
    }

    @Test fun `Live now keeps the server's order`() = runTest(dispatcher) {
        // Best first by the server's rule (votes plus listeners): not by listeners, and not by name.
        val sent = listOf(session("CCCCCCCC", listeners = 2), session("AAAAAAAA", listeners = 40), session("BBBBBBBB", listeners = 9))
        serverOn(live = sent)
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.on).isTrue()
        assertThat(vm.state.value.live).containsExactlyElementsIn(sent).inOrder()
    }

    @Test fun `Your stations are the followed ones as the server sends them, live first`() = runTest(dispatcher) {
        val sent = listOf(
            station("LIVE0001", following = true, live = StationLive(room = "ROOM0001", listeners = 14)),
            station("LIVE0002", following = true, live = StationLive(room = "ROOM0002", listeners = 2)),
            station("OFFAIR01", following = true),
        )
        serverOn(following = sent)
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.following).containsExactlyElementsIn(sent).inOrder()
    }

    @Test fun `Home doesn't ask for your own station, the Sessions screen does`() = runTest(dispatcher) {
        val own = station("MINE0001").copy(mine = true)
        serverOn(mine = own)
        val home = vm()
        home.onShown(); advanceUntilIdle()
        coVerify(exactly = 0) { repo.mine() }
        coVerify { repo.live(SessionsViewModel.HOME_LIVE) }
        val all = vm()
        all.onShown(all = true); advanceUntilIdle()
        assertThat(all.state.value.mine).isEqualTo(own)
        coVerify { repo.live(SessionsViewModel.ALL_LIVE) }
    }

    @Test fun `coming back within 30 seconds doesn't reload, later it does`() = runTest(dispatcher) {
        serverOn(live = listOf(session("AAAAAAAA")))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        now += 29_000
        vm.onShown(); advanceUntilIdle()
        coVerify(exactly = 1) { repo.live(any()) }
        now += 2_000
        vm.onShown(); advanceUntilIdle()
        coVerify(exactly = 2) { repo.live(any()) }
    }

    @Test fun `a follow or a station change made anywhere reloads the lists`() = runTest(dispatcher) {
        serverOn()
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        serverOn(following = listOf(station("NEWFOLLO", following = true)))
        revision.value = 1; advanceUntilIdle()
        assertThat(vm.state.value.following.map { it.id }).containsExactly("NEWFOLLO")
    }

    @Test fun `a reload that fails keeps the list on screen`() = runTest(dispatcher) {
        serverOn(live = listOf(session("AAAAAAAA")))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        coEvery { repo.live(any()) } returns CommunityResult.Failed("offline")
        now += 60_000
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.live).hasSize(1)
        assertThat(vm.state.value.message).isNull()
        // A pulled refresh says it couldn't.
        vm.refresh(); advanceUntilIdle()
        assertThat(vm.state.value.live).hasSize(1)
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(vm.state.value.message).isEqualTo("Couldn't reach Community. Try again.")
    }

    @Test fun `turning Community off clears what was shown`() = runTest(dispatcher) {
        serverOn(live = listOf(session("AAAAAAAA")))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        communityOn.value = false; advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SessionsViewModel.UiState(off = true))
    }

    // ── Tuning in ───────────────────────────────────────────────────────────────

    @Test fun `tuning in sends the room and its station, then opens the player once the room lets us in`() = runTest(dispatcher) {
        val s = session("AAAAAAAA")
        serverOn(live = listOf(s))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        var opened = 0
        vm.tuneIn(s) { opened++ }
        runCurrent()
        verify(exactly = 1) { controller.send(Command.TuneIn(s.room, "AAAAAAAA")) }
        assertThat(vm.state.value.tuningIn).isEqualTo("AAAAAAAA")
        assertThat(opened).isEqualTo(0)
        room.value = ListenTogetherState.Connecting(hosting = false, station = "AAAAAAAA"); runCurrent()
        room.value = inRoom(s.room, station = "AAAAAAAA", role = SessionRole.LISTENER); runCurrent()
        assertThat(opened).isEqualTo(1)
        assertThat(vm.state.value.tuningIn).isNull()
    }

    @Test fun `the first tune-in says who sees your name, and tunes in once that's settled`() = runTest(dispatcher) {
        coEvery { share.tuneInNameSeen() } returns false
        val s = session("AAAAAAAA")
        serverOn(live = listOf(s))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        var opened = 0
        vm.tuneIn(s) { opened++ }; runCurrent()
        // The name as it stands, and nothing sent yet.
        assertThat(vm.state.value.naming).isEqualTo("Rawn")
        verify(exactly = 0) { controller.send(any()) }

        vm.confirmName("DJ Rawn"); runCurrent()
        coVerify { share.setDisplayName("DJ Rawn") }
        coVerify { share.setTuneInNameSeen() }
        assertThat(vm.state.value.naming).isNull()
        verify(exactly = 1) { controller.send(Command.TuneIn(s.room, "AAAAAAAA")) }
        room.value = ListenTogetherState.Connecting(hosting = false, station = "AAAAAAAA"); runCurrent()
        room.value = inRoom(s.room, station = "AAAAAAAA", role = SessionRole.LISTENER); runCurrent()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun `an unchanged name isn't saved again, and backing out sends nothing and asks next time`() = runTest(dispatcher) {
        coEvery { share.tuneInNameSeen() } returns false
        val s = session("AAAAAAAA")
        serverOn(live = listOf(s))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        vm.tuneIn(s) {}; runCurrent()
        vm.cancelName()
        assertThat(vm.state.value.naming).isNull()
        verify(exactly = 0) { controller.send(any()) }
        coVerify(exactly = 0) { share.setTuneInNameSeen() }

        vm.tuneIn(s) {}; runCurrent()
        assertThat(vm.state.value.naming).isEqualTo("Rawn")
        vm.confirmName(" Rawn "); runCurrent()
        coVerify(exactly = 0) { share.setDisplayName(any()) }
        coVerify(exactly = 1) { share.setTuneInNameSeen() }
        verify(exactly = 1) { controller.send(any()) }
    }

    @Test fun `a second tap while tuning in sends nothing more`() = runTest(dispatcher) {
        val a = session("AAAAAAAA"); val b = session("BBBBBBBB")
        serverOn(live = listOf(a, b))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        vm.tuneIn(a) {}; runCurrent()
        vm.tuneIn(a) {}; vm.tuneIn(b) {}; runCurrent()
        verify(exactly = 1) { controller.send(any()) }
    }

    @Test fun `a full session can't be tuned in to`() = runTest(dispatcher) {
        val full = session("FULLFULL", listeners = 50, full = true)
        serverOn(live = listOf(full))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        var opened = 0
        vm.tuneIn(full) { opened++ }; advanceUntilIdle()
        verify(exactly = 0) { controller.send(any()) }
        assertThat(opened).isEqualTo(0)
        assertThat(vm.state.value.tuningIn).isNull()
    }

    @Test fun `already in that session, a tap just opens the player`() = runTest(dispatcher) {
        val s = session("AAAAAAAA")
        serverOn(live = listOf(s))
        room.value = inRoom(s.room, station = "AAAAAAAA", role = SessionRole.LISTENER)
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        var opened = 0
        vm.tuneIn(s) { opened++ }; advanceUntilIdle()
        assertThat(opened).isEqualTo(1)
        verify(exactly = 0) { controller.send(any()) }
    }

    @Test fun `a session that refuses leaves the player shut, and the list reloads`() = runTest(dispatcher) {
        val s = session("AAAAAAAA")
        serverOn(live = listOf(s))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        var opened = 0
        vm.tuneIn(s) { opened++ }; runCurrent()
        // The engine says why in its own toast ("Couldn't join this session", "This session is full"…).
        serverOn(live = emptyList())
        room.value = ListenTogetherState.Connecting(hosting = false, station = "AAAAAAAA"); runCurrent()
        room.value = ListenTogetherState.Idle; advanceUntilIdle()
        assertThat(opened).isEqualTo(0)
        assertThat(vm.state.value.tuningIn).isNull()
        assertThat(vm.state.value.live).isEmpty()
    }

    @Test fun `a room that never answers stops waiting after 20 seconds`() = runTest(dispatcher) {
        val s = session("AAAAAAAA")
        serverOn(live = listOf(s))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        vm.tuneIn(s) {}; runCurrent()
        advanceTimeBy(19_000); runCurrent()
        assertThat(vm.state.value.tuningIn).isEqualTo("AAAAAAAA")
        advanceTimeBy(1_100); advanceUntilIdle()
        assertThat(vm.state.value.tuningIn).isNull()
    }

    @Test fun `while this phone is on the air, another station's card says to end the session first`() = runTest(dispatcher) {
        val s = session("AAAAAAAA")
        serverOn(live = listOf(s))
        // Tuning in leaves the room this phone is in, and a session's host leaving ends it for everyone.
        room.value = inRoom("MYROOM01", station = "MINE0001", role = SessionRole.HOST, isHost = true)
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        var opened = 0
        vm.tuneIn(s) { opened++ }; advanceUntilIdle()
        verify(exactly = 0) { controller.send(any()) }
        assertThat(opened).isEqualTo(0)
        assertThat(vm.state.value.note).isEqualTo(SessionsViewModel.Note.END_YOURS_FIRST)
    }

    @Test fun `a followed station on the air is tuned in to from Your stations`() = runTest(dispatcher) {
        val followed = station("LIVE0001", following = true, live = StationLive(room = "ROOM0001", listeners = 14))
        serverOn(following = listOf(followed))
        val vm = vm()
        vm.onShown(); advanceUntilIdle()
        vm.tuneIn(followed) {}; runCurrent()
        verify(exactly = 1) { controller.send(Command.TuneIn("ROOM0001", "LIVE0001")) }
        // One that's off the air has no room to join.
        val vm2 = vm()
        vm2.tuneIn(station("OFFAIR01", following = true)) {}; runCurrent()
        verify(exactly = 1) { controller.send(any()) }
    }

    private fun inRoom(code: String, station: String, role: SessionRole, isHost: Boolean = false) = ListenTogetherState.InRoom(
        code = code, url = "https://stashfm.app/s/$station", myId = "ME000001", isHost = isHost, hostId = if (isHost) "ME000001" else "HOST0001",
        members = emptyList(), suggestions = emptyList(), session = SessionInfo(station = station), role = role,
    )
}
