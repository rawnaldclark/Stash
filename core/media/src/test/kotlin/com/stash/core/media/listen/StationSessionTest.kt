package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.listen.RoomEvent
import com.stash.core.media.PlayNext
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherSessionTest.FakeConnection
import com.stash.core.media.listen.ListenTogetherSessionTest.FakePlayer
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomRequest
import com.stash.core.model.listen.RoomState
import com.stash.core.model.listen.RoomTimeline
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.listen.SessionInfo
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A station's session (Sessions): what the engine does differently from a private room, which ListenTogetherSessionTest covers. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StationSessionTest {
    val track = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000)
    val next = SharedTrack("Xtal", "Aphex Twin")
    fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    val player = FakePlayer()
    /** Each connect's room code and connection: a move to the station's newer session is a new one. */
    val connections = mutableListOf<Pair<String, FakeConnection>>()
    val connection: FakeConnection get() = connections.last().second
    val catalog = object : SessionCatalog {
        val items = mapOf(track to item("1"), next to item("2"))
        override suspend fun mediaItemFor(track: SharedTrack): MediaItem? = items[track]
        override suspend fun sharedTrackFor(item: MediaItem): SharedTrack? = items.entries.firstOrNull { it.value.mediaId == item.mediaId }?.key
        override suspend fun radioAfter(track: SharedTrack): List<SharedTrack> = emptyList()
    }
    val api: RoomApiClient = mockk()
    val controller = ListenTogetherController(ApplicationProvider.getApplicationContext()).apply { serviceAttached = true }
    var key: String? = KEY
    var keyReads = 0

    fun TestScope.session() = ListenTogetherSession(
        scope = backgroundScope,
        controller = controller,
        player = player,
        catalog = catalog,
        api = api,
        connector = { code, scope, hello -> FakeConnection().also { it.hello = hello; it.scope = scope; connections += code to it } },
        autoplayRadio = { false },
        displayName = { "Rawn" },
        clock = { testScheduler.currentTime },
        communityKey = { keyReads++; key },
    ).also { it.start() }

    /** A session as its room describes it: v 3, Maya hosting ([host] "h"), this phone "me". */
    fun state(
        host: String? = "h",
        track: SharedTrack? = this.track,
        key: Int = 1,
        timeline: RoomTimeline = RoomTimeline(0, 0, false),
        queue: List<SharedTrack> = emptyList(),
        session: SessionInfo? = SessionInfo(STATION, listeners = 1),
    ) = RoomState(
        rev = 1, host = host, track = track, trackKey = key, timeline = timeline, queue = queue,
        members = listOf(RoomMember("h", "Maya", 1, "ok", v = 3), RoomMember("me", "Rawn", 2, "ok", v = 3)),
        v = 3, qv = 0, session = session,
    )

    fun TestScope.receive(m: ServerMessage) { connection.incoming.trySend(RoomEvent.Message(m, receivedAt = testScheduler.currentTime)); runCurrent() }

    fun TestScope.notices(): MutableList<String> {
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { notices += it } }
        return notices
    }

    fun TestScope.tuneIn(code: String = "K7QA2PXM") {
        session()
        controller.send(Command.TuneIn(code, STATION)); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
    }

    fun created(code: String = "K7QA2PXM") = CommunityResult.Ok(RoomApiClient.Created(code, "HOSTKEY", "https://stash-share.rawnaldclark.workers.dev/s/$STATION"))

    /** This phone goes live as [name] ("Rawn", the display name, when the station has no DJ name) and is welcomed as host. */
    fun TestScope.goLive(name: String = "Rawn") {
        coEvery { api.createSession(STATION, name, KEY) } returns created()
        session()
        controller.send(Command.GoLive(STATION, name)); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(host = "me", track = null, key = 0)))
    }

    // ── Tuning in, going live (task 10) ───────────────────────────────────────

    @Test fun `tuning in says v3, the name and the Community key in every hello, resumes too, and never a host key`() = runTest {
        tuneIn()
        assertThat(connections.single().first).isEqualTo("K7QA2PXM")
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Connecting(hosting = false, station = STATION))
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", v = 3, communityKey = KEY))
        receive(ServerMessage.Welcome("me", "tok", state()))
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", resumeToken = "tok", v = 3, communityKey = KEY))
        val room = controller.state.value as ListenTogetherState.InRoom
        assertThat(room.url).isEqualTo("https://stashfm.app/s/$STATION")
        assertThat(room.isHost).isFalse()
        assertThat(player.calls.take(3)).containsExactly("save", "enter:listener", "pause").inOrder()
    }

    @Test fun `a private join never reads or sends the Community key`() = runTest {
        session()
        controller.send(Command.Join("K7QA2PXM")); runCurrent()
        assertThat(connection.hello!!().communityKey).isNull()
        assertThat(keyReads).isEqualTo(0)
    }

    @Test fun `without a Community key nobody tunes in, and nothing moves`() = runTest {
        key = null
        val notices = notices()
        session()
        controller.send(Command.TuneIn("K7QA2PXM", STATION)); runCurrent()
        assertThat(connections).isEmpty()
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
        assertThat(controller.active.value).isFalse()
        assertThat(player.calls).isEmpty()
        assertThat(notices).containsExactly("Couldn't join this session")
    }

    @Test fun `tuning in from a room leaves it first, and tuning in again to the same one does nothing`() = runTest {
        session()
        controller.send(Command.Join("ABCDEFGH")); runCurrent()
        val private = connection
        controller.send(Command.TuneIn("K7QA2PXM", STATION)); runCurrent()
        assertThat(private.closed).isTrue()
        assertThat(connections.map { it.first }).containsExactly("ABCDEFGH", "K7QA2PXM").inOrder()
        assertThat(connection.hello!!().communityKey).isEqualTo(KEY)
        controller.send(Command.TuneIn("K7QA2PXM", STATION)); runCurrent()
        assertThat(connections).hasSize(2)
    }

    @Test fun `going live opens the session under the key, and the user's queue becomes the room's`() = runTest {
        player.queue = UserQueue(item("1"), listOf(item("2")), 0)
        player.positionMs = 30_000
        coEvery { api.createSession(STATION, "DJ Rawn", KEY) } returns created()
        session()
        controller.send(Command.GoLive(STATION, " DJ Rawn ")); runCurrent()
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Connecting(hosting = true, station = STATION))
        assertThat(player.calls.take(3)).containsExactly("save", "enter:host", "pause").inOrder()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        // The hello names the host as the session does, so the crowd and Live now's card agree.
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "DJ Rawn", hostKey = "HOSTKEY", v = 3))
        receive(ServerMessage.Welcome("me", "tok", state(host = "me", track = null, key = 0)))
        val load = connection.sent.filterIsInstance<ClientMessage.Load>().single()
        assertThat(load.track).isEqualTo(track)
        assertThat(load.positionMs).isEqualTo(30_000)
        assertThat(load.queue).containsExactly(next)
        // A session checks the host key on a resume too.
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "DJ Rawn", hostKey = "HOSTKEY", resumeToken = "tok", v = 3))
        val room = controller.state.value as ListenTogetherState.InRoom
        assertThat(room.isHost).isTrue()
        assertThat(room.url).isEqualTo("https://stashfm.app/s/$STATION") // built from the id, whichever host answered
    }

    @Test fun `a station without a DJ name goes live under the display name`() = runTest {
        coEvery { api.createSession(STATION, "Rawn", KEY) } returns created()
        session()
        controller.send(Command.GoLive(STATION, "  ")); runCurrent()
        assertThat(connection.hello!!().name).isEqualTo("Rawn")
        coVerify(exactly = 1) { api.createSession(STATION, "Rawn", KEY) }
    }

    @Test fun `each refusal of going live says why and changes nothing`() = runTest {
        val notices = notices()
        session()
        val answers = listOf(
            CommunityResult.Rejected("sessions_off") to "Going live isn't available right now",
            CommunityResult.Rejected("gone") to "Your station is off the air",
            CommunityResult.Rejected("not_yours") to "That station isn't yours",
            CommunityResult.Rejected("blocked") to "You can't go live in Community",
            CommunityResult.Rejected("rate_limited") to "Slow down a moment, then try again",
            CommunityResult.Rejected("bad_key") to "Couldn't go live. Try again.",
            CommunityResult.Failed("timeout") to "Couldn't go live. Check your connection and try again.",
        )
        for ((answer, _) in answers) {
            coEvery { api.createSession(any(), any(), any()) } returns answer
            controller.send(Command.GoLive(STATION)); runCurrent()
            assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
        }
        assertThat(notices).containsExactlyElementsIn(answers.map { it.second }).inOrder()
        assertThat(connections).isEmpty()
        assertThat(controller.active.value).isFalse()
        assertThat(player.calls).isEmpty()
    }

    @Test fun `without a Community key going live asks nothing`() = runTest {
        key = null
        val notices = notices()
        session()
        controller.send(Command.GoLive(STATION)); runCurrent()
        assertThat(notices).containsExactly("Couldn't go live. Check your connection and try again.")
        coVerify(exactly = 0) { api.createSession(any(), any(), any()) }
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
    }

    @Test fun `going live from a private room says to end it, from someone's session to leave it, and while live does nothing`() = runTest {
        val notices = notices()
        session()
        controller.send(Command.Join("ABCDEFGH")); runCurrent()
        controller.send(Command.GoLive(STATION)); runCurrent()
        controller.send(Command.TuneIn("K7QA2PXM", STATION)); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state()))
        controller.send(Command.GoLive(STATION)); runCurrent()
        assertThat(notices).containsExactly("End Listen Together first", "Leave this session first").inOrder()
        coVerify(exactly = 0) { api.createSession(any(), any(), any()) }
        assertThat(connections).hasSize(2) // still in the session
    }

    @Test fun `going live while live does nothing`() = runTest {
        val notices = notices()
        goLive()
        controller.send(Command.GoLive(STATION)); runCurrent()
        assertThat(notices).isEmpty()
        assertThat(connections).hasSize(1)
        coVerify(exactly = 1) { api.createSession(any(), any(), any()) }
    }

    // ── What the app knows about a session (task 11) ──────────────────────────

    fun inRoom() = controller.state.value as ListenTogetherState.InRoom
    fun TestScope.gathered() { advanceTimeBy(400); runCurrent() }
    fun playNext(id: String): MediaItem = PlayNext.tag(item(id))
    fun requests() = connection.sent.filterIsInstance<ClientMessage.Request>()

    /** Tuned in and welcomed as a listener, with nothing sent yet. */
    fun TestScope.listening(state: RoomState = state()) {
        tuneIn()
        receive(ServerMessage.Welcome("me", "tok", state))
        connection.sent.clear()
    }

    @Test fun `the welcome's session and each session frame reach the room`() = runTest {
        listening()
        assertThat(inRoom().session).isEqualTo(SessionInfo(STATION, listeners = 1))
        receive(ServerMessage.Session(STATION, listeners = 5, autoAccept = true, presence = SessionInfo.AWAY))
        assertThat(inRoom().session).isEqualTo(SessionInfo(STATION, 5, emptyList(), true, SessionInfo.AWAY))
        assertThat(inRoom().roomKnowsAdds).isFalse() // no guests-add switch in a session
    }

    @Test fun `a private room has no session, role, requests or on air, and the rest is as before`() = runTest {
        session()
        controller.send(Command.Join("ABCDEFGH")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(timeline = RoomTimeline(0, 0, true), session = null).copy(v = 2, guestsAdd = true)))
        player.events!!.onReady(); runCurrent()
        val room = inRoom()
        assertThat(room.session).isNull()
        assertThat(room.role).isNull()
        assertThat(room.requests).isEmpty()
        assertThat(room.onAir).isFalse()
        assertThat(room.roomKnowsAdds).isTrue()
        assertThat(room.guestsAdd).isTrue()
    }

    @Test fun `the role follows this phone's id - the host, a co-host, else a listener`() = runTest {
        listening()
        assertThat(inRoom().role).isEqualTo(SessionRole.LISTENER)
        receive(ServerMessage.Session(STATION, listeners = 1, cohosts = listOf("me")))
        assertThat(inRoom().role).isEqualTo(SessionRole.COHOST)
        receive(ServerMessage.Session(STATION, listeners = 1))
        assertThat(inRoom().role).isEqualTo(SessionRole.LISTENER)
    }

    @Test fun `going live makes this phone the session's host`() = runTest {
        goLive()
        assertThat(inRoom().role).isEqualTo(SessionRole.HOST)
    }

    @Test fun `the requests this phone may see are kept`() = runTest {
        listening()
        val mine = RoomRequest("r1", "me", next)
        receive(ServerMessage.Requests(listOf(mine)))
        assertThat(inRoom().requests).containsExactly(mine)
    }

    @Test fun `a listener's Play next is a request, never an add or a suggestion, and the toast waits for the answer`() = runTest {
        val notices = notices()
        listening()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        assertThat(requests()).containsExactly(ClientMessage.Request(next))
        assertThat(connection.sent.filterIsInstance<ClientMessage.Add>()).isEmpty()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Suggest>()).isEmpty()
        assertThat(notices).isEmpty()
        receive(ServerMessage.RequestAnswer("waiting"))
        assertThat(notices).containsExactly("1 request sent to Maya")
    }

    @Test fun `with Auto-accept a listener's Add to queue goes straight in`() = runTest {
        val notices = notices()
        listening()
        player.interceptor!!.onAdd(listOf(item("2"))); gathered()
        assertThat(requests()).containsExactly(ClientMessage.Request(next))
        receive(ServerMessage.RequestAnswer("queued"))
        assertThat(notices).containsExactly("Added to the session queue")
    }

    @Test fun `only as many requests as can wait are sent, and the toast says how many can`() = runTest {
        val notices = notices()
        listening()
        receive(ServerMessage.Requests(listOf(RoomRequest("r1", "me", track), RoomRequest("r2", "me", track))))
        player.interceptor!!.onAdd(listOf(playNext("1"), playNext("2"))); gathered()
        assertThat(requests()).containsExactly(ClientMessage.Request(track))
        receive(ServerMessage.RequestAnswer("waiting"))
        assertThat(notices).containsExactly("You have 3 requests waiting")
    }

    @Test fun `a listener with 3 waiting sends nothing and hears why at once`() = runTest {
        val notices = notices()
        listening()
        receive(ServerMessage.Requests(List(3) { RoomRequest("r$it", "me", track) }))
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        assertThat(requests()).isEmpty()
        assertThat(notices).containsExactly("You have 3 requests waiting")
    }

    @Test fun `a session that can't take the request says so`() = runTest {
        val notices = notices()
        listening()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        receive(ServerMessage.RequestAnswer("full"))
        assertThat(notices).containsExactly("The session can't take more requests right now")
    }

    @Test fun `a pass reaches its requester with who passed`() = runTest {
        val notices = notices()
        listening()
        receive(ServerMessage.RequestPassed(next, by = "h"))
        assertThat(notices).containsExactly("Maya passed on Xtal")
    }

    @Test fun `a request made while the socket is down goes after the next welcome`() = runTest {
        val notices = notices()
        listening()
        connection.up = false
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        assertThat(notices).containsExactly("Not connected right now. It'll go in when you're back.")
        connection.up = true
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state()))
        assertThat(requests()).containsExactly(ClientMessage.Request(next))
    }

    @Test fun `a listener's tap on a song points to Request, and a co-host's adds go straight in`() = runTest {
        val notices = notices()
        listening()
        player.interceptor!!.onSet(listOf(item("2")), 0)
        assertThat(notices).containsExactly("Only the host can change the song. Request it instead.")
        receive(ServerMessage.Session(STATION, listeners = 1, cohosts = listOf("me")))
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Add>()).containsExactly(ClientMessage.Add(listOf(next), next = true))
        assertThat(requests()).isEmpty()
    }

    @Test fun `a co-host takes out anyone's song, a listener only their own`() = runTest {
        val theirs = next.copy(addedBy = "h")
        listening(state(queue = listOf(theirs)))
        controller.send(Command.Unqueue(theirs, 0)); runCurrent()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Unqueue>()).isEmpty()
        receive(ServerMessage.Session(STATION, listeners = 1, cohosts = listOf("me")))
        controller.send(Command.Unqueue(theirs, 0)); runCurrent()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Unqueue>()).containsExactly(ClientMessage.Unqueue(theirs, 0))
    }

    @Test fun `on air while the room's song plays here - not before it's ready, paused here, reconnecting or paused`() = runTest {
        listening(state(timeline = RoomTimeline(0, 0, true)))
        assertThat(inRoom().onAir).isFalse() // still loading
        player.events!!.onReady(); runCurrent()
        assertThat(inRoom().onAir).isTrue()
        player.interceptor!!.onPause(); runCurrent() // a listener pauses only their own phone
        assertThat(inRoom().onAir).isFalse()
        controller.rejoin(); runCurrent()
        assertThat(inRoom().onAir).isTrue()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        assertThat(inRoom().onAir).isFalse()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(timeline = RoomTimeline(0, 0, true))))
        assertThat(inRoom().onAir).isTrue()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 5_000, atRoomMs = 0, playing = false, by = "h"))
        assertThat(inRoom().onAir).isFalse()
    }

    companion object {
        const val STATION = "Ab3dEf9h"
        val KEY = "k".repeat(43)
    }
}
