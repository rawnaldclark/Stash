package com.stash.feature.nowplaying.session

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherController.RequestAnswer
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.Track
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomRequest
import com.stash.core.model.listen.SessionInfo
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A session's requests, co-hosts and booting: the rules ([SessionDjUi]) and what each tap sends. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDjTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val nights = SharedTrack("Nights", "Frank Ocean")
    private val ivy = SharedTrack("Ivy", "Frank Ocean")
    private val xtal = SharedTrack("Xtal", "Aphex Twin")

    private fun room(
        role: SessionRole = SessionRole.LISTENER,
        session: SessionInfo = SessionInfo("St4tion1", listeners = 2),
        queue: List<SharedTrack> = emptyList(),
        requests: List<RoomRequest> = emptyList(),
        members: List<RoomMember> = listOf(RoomMember("h", "Maya"), RoomMember("me", "Rawn"), RoomMember("b", "Ben")),
        names: Map<String, String?> = emptyMap(),
    ) = ListenTogetherState.InRoom(
        code = "K7QA2PXM", url = "https://stashfm.app/s/St4tion1", myId = "me", isHost = role == SessionRole.HOST,
        hostId = if (role == SessionRole.HOST) "me" else "h", members = members, suggestions = emptyList(),
        queue = queue, session = session, role = role, requests = requests, names = names,
    )

    // ── the rules ───────────────────────────────────────────────────────

    @Test fun `where the host is, and whether requests wait or play`() {
        assertThat(hostPresence(SessionInfo("s"))).isEqualTo(HostPresence.BOOTH)
        assertThat(hostPresence(SessionInfo("s", autoAccept = true))).isEqualTo(HostPresence.BOOTH_AUTO)
        assertThat(hostPresence(SessionInfo("s", presence = SessionInfo.AWAY))).isEqualTo(HostPresence.AWAY_WAIT)
        assertThat(hostPresence(SessionInfo("s", presence = SessionInfo.AWAY, autoAccept = true))).isEqualTo(HostPresence.AWAY_AUTO)
    }

    @Test fun `the host's name comes from the members, then from who was seen, else nothing`() {
        assertThat(hostName(room())).isEqualTo("Maya")
        assertThat(hostName(room(members = listOf(RoomMember("me", "Rawn")), names = mapOf("h" to "Maya")))).isEqualTo("Maya")
        assertThat(hostName(room(members = listOf(RoomMember("h", " "), RoomMember("me", "Rawn"))))).isNull()
    }

    @Test fun `my requests are my songs in Up next, in order with their places, then the ones still waiting`() {
        val r = room(
            queue = listOf(xtal.copy(addedBy = "h"), nights.copy(addedBy = "me"), ivy.copy(addedBy = "b")),
            requests = listOf(RoomRequest("r1", "me", ivy), RoomRequest("r2", "b", xtal)),
        )
        assertThat(myRequests(r)).containsExactly(
            MyRequest(nights.copy(addedBy = "me"), accepted = true, queueIndex = 1),
            MyRequest(ivy, accepted = false),
        ).inOrder()
        assertThat(myWaiting(r)).isEqualTo(1)
    }

    @Test fun `the booth lists every request with who asked`() {
        val r = room(
            role = SessionRole.HOST,
            requests = listOf(RoomRequest("r1", "b", nights), RoomRequest("r2", "gone", ivy), RoomRequest("r3", "x", xtal)),
            names = mapOf("gone" to "Sam"),
        )
        assertThat(waitingRequests(r)).containsExactly(
            WaitingRequest("r1", nights, "Ben"), WaitingRequest("r2", ivy, "Sam"), WaitingRequest("r3", xtal, null),
        ).inOrder()
    }

    @Test fun `the host and co-hosts answer requests, a listener doesn't`() {
        assertThat(answersRequests(room(SessionRole.HOST))).isTrue()
        assertThat(answersRequests(room(SessionRole.COHOST))).isTrue()
        assertThat(answersRequests(room(SessionRole.LISTENER))).isFalse()
    }

    @Test fun `only the host acts on people, never on themselves`() {
        assertThat(memberActions(room(SessionRole.LISTENER), "b")).isNull()
        assertThat(memberActions(room(SessionRole.COHOST), "b")).isNull()
        val host = room(SessionRole.HOST)
        assertThat(memberActions(host, "me")).isNull()
        assertThat(memberActions(host, "b")).isEqualTo(MemberActions(canCohost = true, isCohost = false, canBoot = true))
    }

    @Test fun `a 4th co-host isn't offered, but taking the part away always is`() {
        val full = room(SessionRole.HOST, session = SessionInfo("s", cohosts = listOf("c1", "c2", "c3")))
        assertThat(memberActions(full, "b")).isEqualTo(MemberActions(canCohost = false, isCohost = false, canBoot = true))
        assertThat(memberActions(full, "c2")).isEqualTo(MemberActions(canCohost = true, isCohost = true, canBoot = true))
    }

    // ── the view model ──────────────────────────────────────────────────

    private val controller: ListenTogetherController = mockk(relaxed = true)
    private val player: PlayerRepository = mockk(relaxed = true)
    private val music: MusicRepository = mockk()

    private fun track(id: Long, title: String) = Track(id = id, title = title, artist = "Artist")

    @Test fun `typing searches the library after a moment, two letters at least, 30 results at most`() = runTest(dispatcher) {
        every { music.search("ni") } returns flowOf((1L..40L).map { track(it, "Nights $it") })
        val vm = SessionDjViewModel(controller, player, music)
        backgroundScope.launch { vm.results.collect() }

        vm.setQuery("n"); advanceTimeBy(400); runCurrent()
        assertThat(vm.results.value).isEmpty()
        verify(exactly = 0) { music.search(any()) }

        vm.setQuery(" ni "); advanceTimeBy(100); runCurrent()
        assertThat(vm.results.value).isEmpty() // still typing

        advanceTimeBy(300); runCurrent()
        assertThat(vm.results.value).hasSize(30)

        vm.setQuery(""); runCurrent()
        assertThat(vm.results.value).isEmpty()
    }

    @Test fun `a request is the same add every song menu makes`() = runTest(dispatcher) {
        coEvery { player.addToQueue(any<Track>()) } returns true
        val vm = SessionDjViewModel(controller, player, music)
        val song = track(7, "Nights")
        vm.request(song); runCurrent()
        coVerify(exactly = 1) { player.addToQueue(song) }
    }

    @Test fun `each tap sends its command`() = runTest(dispatcher) {
        val vm = SessionDjViewModel(controller, player, music)
        vm.answer("r1", RequestAnswer.NEXT)
        vm.answer("r2", RequestAnswer.PASS)
        vm.setAutoAccept(true)
        vm.setCohost("b", true)
        vm.boot("b")
        vm.takeBack(nights, 3)
        vm.skip()
        verify { controller.send(Command.Skip) }
        verify { controller.send(Command.AnswerRequest("r1", RequestAnswer.NEXT)) }
        verify { controller.send(Command.AnswerRequest("r2", RequestAnswer.PASS)) }
        verify { controller.send(Command.SetAutoAccept(true)) }
        verify { controller.send(Command.SetCohost("b", true)) }
        verify { controller.send(Command.Boot("b")) }
        verify { controller.send(Command.Unqueue(nights, 3)) }
    }
}
