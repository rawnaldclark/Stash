package com.stash.feature.nowplaying.session

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
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
        coEvery { repo.station("MAYA0001") } returns CommunityResult.Ok(StationPage(maya))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = SessionRoomViewModel(repo, controller)

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
