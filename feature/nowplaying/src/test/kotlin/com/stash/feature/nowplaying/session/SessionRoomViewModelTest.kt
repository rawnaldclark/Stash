package com.stash.feature.nowplaying.session

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.community.VoteCounts
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.ui.components.VotePillUi
import com.stash.feature.nowplaying.session.SessionRoomViewModel.Note
import io.mockk.verify
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.community.FollowState
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationPage
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.SessionInfo
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class SessionRoomViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<StationsRepository>(relaxed = true)
    private val controller = mockk<ListenTogetherController>(relaxed = true)
    private val state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)

    private val maya = Station(id = "MAYA0001", name = "Maya's Desk Radio", hostName = "Maya", vibe = "late-night r&b", followers = 38, up = 31, down = 8)

    private fun member(id: String, name: String?, joinedAt: Long) = RoomMember(id = id, name = name, joinedAt = joinedAt)

    private fun room(
        station: String? = "MAYA0001",
        members: List<RoomMember> = listOf(member("HOST0001", "Maya", 1), member("ME000001", "Rawn", 5)),
        cohosts: List<String> = emptyList(),
        track: SharedTrack? = null,
        names: Map<String, String?> = emptyMap(),
    ) = ListenTogetherState.InRoom(
        code = "ROOM0001", url = "https://stashfm.app/s/$station", myId = "ME000001", isHost = false, hostId = "HOST0001",
        members = members, suggestions = emptyList(), track = track, names = names,
        session = station?.let { SessionInfo(station = it, listeners = members.size - 1, cohosts = cohosts) },
        role = station?.let { SessionRole.LISTENER },
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { controller.state } returns state
        every { repo.heardIds } returns heardIds
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(maya))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private val heardIds = MutableStateFlow<Set<String>>(emptySet())
    private var now = 1_000_000L

    private fun vm() = SessionRoomViewModel(repo, controller).also { it.clock = { now } }

    // ── The station's details ───────────────────────────────────────────────────

    @Test fun `entering a session reads its station, once`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value.station).isNull()
        coVerify(exactly = 0) { repo.station(any()) }
        state.value = room(); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(maya)
        // The room's own frames (a new song, someone joining) don't ask again.
        state.value = room(track = SharedTrack("Pink + White", "Frank Ocean")); advanceUntilIdle()
        coVerify(exactly = 1) { repo.station("MAYA0001") }
    }

    @Test fun `a private room reads nothing, and leaving the session forgets the station`() = runTest(dispatcher) {
        val vm = vm()
        state.value = room(station = null); advanceUntilIdle()
        coVerify(exactly = 0) { repo.station(any()) }
        state.value = room(); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(maya)
        state.value = ListenTogetherState.Idle; advanceUntilIdle()
        assertThat(vm.state.value.station).isNull()
    }

    @Test fun `another station's session reads that station`() = runTest(dispatcher) {
        val dilla = Station(id = "DILLA001", name = "Late Night Dilla")
        coEvery { repo.station("DILLA001") } returns CommunityResult.Ok(StationPage(dilla))
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        state.value = room(station = "DILLA001"); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(dilla)
    }

    // ── Follow ──────────────────────────────────────────────────────────────────

    @Test fun `Follow shows at once, takes the server's count, then reads the station again`() = runTest(dispatcher) {
        val answer = CompletableDeferred<CommunityResult<FollowState>>()
        coEvery { repo.follow("MAYA0001", true) } coAnswers { answer.await() }
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.follow(); runCurrent()
        assertThat(vm.state.value.station).isEqualTo(maya.copy(following = true, followers = 39))
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(maya.copy(following = true, followers = 41)))
        answer.complete(CommunityResult.Ok(FollowState(following = true, followers = 40))); advanceUntilIdle()
        // The station as the server has it now.
        assertThat(vm.state.value.station).isEqualTo(maya.copy(following = true, followers = 41))
        coVerify(exactly = 2) { repo.station("MAYA0001") }
    }

    @Test fun `tapping Following stops following`() = runTest(dispatcher) {
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(maya.copy(following = true)))
        coEvery { repo.follow("MAYA0001", false) } returns CommunityResult.Ok(FollowState(following = false, followers = 37))
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.follow(); runCurrent()
        coVerify { repo.follow("MAYA0001", false) }
    }

    @Test fun `a follow that fails rolls back and says why`() = runTest(dispatcher) {
        coEvery { repo.follow(any(), any()) } returns CommunityResult.Rejected("too_many")
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.follow(); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(maya)
        assertThat(vm.state.value.message).isEqualTo("You follow 200 stations. Unfollow one to follow another.")
    }

    @Test fun `a second tap while a follow is out is ignored, and your own station can't be followed`() = runTest(dispatcher) {
        val answer = CompletableDeferred<CommunityResult<FollowState>>()
        coEvery { repo.follow("MAYA0001", true) } coAnswers { answer.await() }
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.follow(); vm.follow(); runCurrent()
        coVerify(exactly = 1) { repo.follow(any(), any()) }
        answer.complete(CommunityResult.Ok(FollowState(true, 39))); advanceUntilIdle()

        coEvery { repo.station("MINE0001") } returns CommunityResult.Ok(StationPage(Station(id = "MINE0001", name = "Mine", mine = true)))
        state.value = room(station = "MINE0001"); advanceUntilIdle()
        vm.follow(); advanceUntilIdle()
        coVerify(exactly = 0) { repo.follow("MINE0001", any()) }
    }

    // ── The vote ────────────────────────────────────────────────────────────────

    private val heard = maya.copy(heard = true)

    @Test fun `the pill is dimmed until the station is heard, by the server's word or this phone's own mark`() = runTest(dispatcher) {
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        assertThat(vm.votePill()).isEqualTo(VotePillUi(23, upLit = false, downLit = false, enabled = true, listenFirst = true, upDimmed = true, downDimmed = true))
        // Two minutes in its sessions, counted on this phone before the server knows.
        heardIds.value = setOf("MAYA0001"); advanceUntilIdle()
        assertThat(vm.votePill()).isEqualTo(VotePillUi(23, upLit = false, downLit = false, enabled = true))
        // Or the server already has it.
        heardIds.value = emptySet()
        coEvery { repo.station("DILLA001") } returns CommunityResult.Ok(StationPage(Station(id = "DILLA001", name = "Dilla", up = 4, heard = true)))
        state.value = room(station = "DILLA001"); advanceUntilIdle()
        assertThat(vm.votePill()!!.listenFirst).isFalse()
    }

    @Test fun `a dimmed arrow says to listen first and sends nothing`() = runTest(dispatcher) {
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.vote(1); advanceUntilIdle()
        assertThat(vm.state.value.note).isEqualTo(Note.LISTEN_FIRST)
        coVerify(exactly = 0) { repo.vote(any(), any()) }
    }

    @Test fun `a vote shows at once, then takes the server's counts`() = runTest(dispatcher) {
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(heard))
        val answer = CompletableDeferred<CommunityResult<VoteCounts>>()
        coEvery { repo.vote("MAYA0001", 1) } coAnswers { answer.await() }
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.vote(1); runCurrent()
        assertThat(vm.state.value.station).isEqualTo(heard.copy(up = 32, myVote = 1))
        answer.complete(CommunityResult.Ok(VoteCounts(up = 40, down = 8, myVote = 1))); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(heard.copy(up = 40, myVote = 1))
    }

    @Test fun `tapping your vote again takes it back, heard or not`() = runTest(dispatcher) {
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(maya.copy(myVote = -1)))
        coEvery { repo.vote("MAYA0001", 0) } returns CommunityResult.Ok(VoteCounts(up = 31, down = 7, myVote = 0))
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.vote(-1); advanceUntilIdle()
        coVerify { repo.vote("MAYA0001", 0) }
        assertThat(vm.state.value.station!!.myVote).isEqualTo(0)
        assertThat(vm.state.value.station!!.down).isEqualTo(7)
    }

    @Test fun `a refused vote flips back and says why`() = runTest(dispatcher) {
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(heard))
        coEvery { repo.vote(any(), any()) } returns CommunityResult.Failed("offline")
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.vote(-1); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(heard)
        assertThat(vm.state.value.message).isEqualTo("Couldn't reach Community. Try again.")
    }

    @Test fun `the server wanting a listen dims the pill again, and a listen that couldn't be sent says try again`() = runTest(dispatcher) {
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(heard))
        coEvery { repo.vote(any(), any()) } returns CommunityResult.Rejected(CommunityRepository.LISTEN_FIRST)
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        vm.vote(1); advanceUntilIdle()
        assertThat(vm.state.value.note).isEqualTo(Note.LISTEN_FIRST)
        assertThat(vm.votePill()!!.listenFirst).isTrue()
        assertThat(vm.state.value.station!!.myVote).isEqualTo(0)

        vm.noteShown()
        coEvery { repo.station("DILLA001") } returns CommunityResult.Ok(StationPage(Station(id = "DILLA001", name = "Dilla", heard = true)))
        coEvery { repo.vote("DILLA001", 1) } returns CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW)
        state.value = room(station = "DILLA001"); advanceUntilIdle()
        vm.vote(1); advanceUntilIdle()
        assertThat(vm.state.value.note).isEqualTo(Note.TRY_AGAIN)
        assertThat(vm.votePill()!!.listenFirst).isFalse()
    }

    // ── Leaving ─────────────────────────────────────────────────────────────────

    @Test fun `leaving within 2 minutes just leaves`() = runTest(dispatcher) {
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        now += 119_000
        vm.leave(); advanceUntilIdle()
        verify(exactly = 1) { controller.send(Command.Leave) }
        assertThat(vm.state.value.parting).isNull()
    }

    @Test fun `leaving after 2 minutes leaves, then asks about the station, once for that session`() = runTest(dispatcher) {
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        now += 120_000
        vm.leave(); advanceUntilIdle()
        verify(exactly = 1) { controller.send(Command.Leave) }
        assertThat(vm.state.value.parting).isEqualTo(maya)
        // The room is gone, the sheet stays with its station until it's closed.
        state.value = ListenTogetherState.Idle; advanceUntilIdle()
        assertThat(vm.state.value.station).isNull()
        assertThat(vm.state.value.parting).isEqualTo(maya)
        vm.partingDone()
        assertThat(vm.state.value.parting).isNull()
        // Back in the same session and out again: it doesn't ask twice.
        state.value = room(); advanceUntilIdle()
        now += 300_000
        vm.leave(); advanceUntilIdle()
        assertThat(vm.state.value.parting).isNull()
    }

    @Test fun `the sheet's vote and Follow work after the room is gone`() = runTest(dispatcher) {
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(heard))
        coEvery { repo.vote("MAYA0001", 1) } returns CommunityResult.Ok(VoteCounts(up = 32, down = 8, myVote = 1))
        coEvery { repo.follow("MAYA0001", true) } returns CommunityResult.Ok(FollowState(following = true, followers = 39))
        val vm = vm()
        state.value = room(); advanceUntilIdle()
        now += 200_000
        vm.leave()
        state.value = ListenTogetherState.Idle; advanceUntilIdle()
        vm.vote(1); advanceUntilIdle()
        assertThat(vm.state.value.parting!!.myVote).isEqualTo(1)
        vm.follow(); advanceUntilIdle()
        assertThat(vm.state.value.parting!!.following).isTrue()
        assertThat(vm.state.value.parting!!.followers).isEqualTo(39)
    }

    @Test fun `a host's Leave, and your own station, never ask`() = runTest(dispatcher) {
        coEvery { repo.station("MINE0001") } returns CommunityResult.Ok(StationPage(Station(id = "MINE0001", name = "Mine", mine = true)))
        val vm = vm()
        state.value = room(station = "MINE0001").copy(isHost = true, hostId = "ME000001", role = SessionRole.HOST); advanceUntilIdle()
        now += 600_000
        vm.leave(); advanceUntilIdle()
        verify(exactly = 1) { controller.send(Command.Leave) }
        assertThat(vm.state.value.parting).isNull()
    }

    // ── Whose pick ──────────────────────────────────────────────────────────────

    @Test fun `a listener's or co-host's song says whose pick it is, the host's own says nothing`() {
        val ben = member("BEN00001", "Ben", 9)
        val everyone = listOf(member("HOST0001", "Maya", 1), member("ME000001", "Rawn", 5), ben)
        fun song(by: String?) = SharedTrack("Xtal", "Aphex Twin", addedBy = by)
        assertThat(pickOf(room(members = everyone, track = song("BEN00001")))).isEqualTo(Pick("BEN00001", "Ben", mine = false))
        assertThat(pickOf(room(members = everyone, track = song("ME000001")))).isEqualTo(Pick("ME000001", "Rawn", mine = true))
        assertThat(pickOf(room(members = everyone, track = song("HOST0001")))).isNull()
        assertThat(pickOf(room(members = everyone, track = song(null)))).isNull()
        assertThat(pickOf(room(members = everyone, track = null))).isNull()
        // Someone who has left: the room still remembers their name.
        assertThat(pickOf(room(track = song("GONE0001"), names = mapOf("GONE0001" to "Lee")))).isEqualTo(Pick("GONE0001", "Lee", mine = false))
    }

    // ── The crowd ───────────────────────────────────────────────────────────────

    @Test fun `the crowd is the booth, then the floor in the order they joined, with the rest counted`() {
        val members = listOf(
            member("LATE0001", "Zed", 90), member("ME000001", "Rawn", 30), member("COHOST01", "Sam", 20),
            member("HOST0001", "Maya", 1), member("EARLY001", "Ana", 10), member("MID00001", "Jo", 40),
        )
        val crowd = crowdOf(room(members = members, cohosts = listOf("COHOST01")), fits = 3)
        // The host first (the crown), then co-hosts.
        assertThat(crowd.booth).containsExactly(Seat(members[3], host = true), Seat(members[2], host = false)).inOrder()
        // Everyone else, earliest first; three fit, one more is counted.
        assertThat(crowd.floor.map { it.id }).containsExactly("EARLY001", "ME000001", "MID00001").inOrder()
        assertThat(crowd.more).isEqualTo(1)
        assertThat(crowd.everyone.map { it.id }).containsExactly("HOST0001", "COHOST01", "EARLY001", "ME000001", "MID00001", "LATE0001").inOrder()
    }

    @Test fun `a host who has dropped for a moment leaves the booth to the co-hosts`() {
        val members = listOf(member("COHOST01", "Sam", 20), member("ME000001", "Rawn", 30))
        val crowd = crowdOf(room(members = members, cohosts = listOf("COHOST01", "GONE0001")), fits = 6)
        assertThat(crowd.booth).containsExactly(Seat(members[0], host = false))
        assertThat(crowd.floor.map { it.id }).containsExactly("ME000001")
        assertThat(crowd.more).isEqualTo(0)
    }

    // ── Reactions ───────────────────────────────────────────────────────────────

    @Test fun `reactions are drawn at most 4 a second`() {
        val gate = ReactionGate()
        // A full room: 50 frames in one second. Four get a floater; the rest still count for the song.
        val drawn = (0 until 50).count { gate.allow(now = 1_000L + it * 20) }
        assertThat(drawn).isEqualTo(4)
        // A second after the first, one more may go.
        assertThat(gate.allow(now = 2_005L)).isTrue()
        // A slow room is never held back.
        val slow = ReactionGate()
        assertThat((0 until 5).all { slow.allow(now = it * 300L) }).isTrue()
    }
}
