package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.listen.CloseReason
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
    /** The station's room on the air, as its page says; every ask counted. */
    var liveRoom: () -> String? = { null }
    var liveRoomAsks = 0
    /** Each piece of listening time given to the station, as (station, ms). */
    val listened = mutableListOf<Pair<String, Long>>()

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
        stationRoom = { station -> check(station == STATION); liveRoomAsks++; liveRoom() },
        stationListened = { station, ms -> listened += station to ms },
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

    // ── How a session ends (task 12) ──────────────────────────────────────────

    /** Each time the user's own queue is put back (PlayerRepositoryImpl restores on it). */
    fun TestScope.restores(): MutableList<Unit> {
        val out = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.sessionEnds.collect { out += it } }
        return out
    }

    /** On the engine already running: tune in again and be welcomed, as a listener. */
    fun TestScope.tuneInAgain(state: RoomState = state()) {
        controller.send(Command.TuneIn("K7QA2PXM", STATION)); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state))
    }

    /** On the engine already running: go live again and be welcomed, as the host of a playing room. */
    fun TestScope.goLiveAgain(state: RoomState = state(host = "me", timeline = RoomTimeline(0, 0, true))) {
        controller.send(Command.GoLive(STATION, "Rawn")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state))
    }

    @Test fun `a listener hears whose session ended, and gets their queue back`() = runTest {
        val notices = notices()
        val restores = restores()
        session()
        val reasons = listOf("host_ended", "paused", "closed", "something_new")
        for (reason in reasons) {
            tuneInAgain()
            receive(ServerMessage.Ended(reason))
            assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
            assertThat(player.calls.last()).isEqualTo("exit")
        }
        assertThat(notices).containsExactlyElementsIn(reasons.map { "Maya's session ended" })
        assertThat(restores).hasSize(reasons.size)
        assertThat(liveRoomAsks).isEqualTo(0)
    }

    @Test fun `without the host's name it's the session that ended`() = runTest {
        val notices = notices()
        session()
        tuneInAgain(state().copy(members = listOf(RoomMember("h", null, 1, "ok", v = 3), RoomMember("me", "Rawn", 2, "ok", v = 3))))
        receive(ServerMessage.Ended("host_ended"))
        assertThat(notices).containsExactly("The session ended")
    }

    @Test fun `removed, or off the air, a listener hears which`() = runTest {
        val notices = notices()
        val restores = restores()
        session()
        tuneInAgain()
        receive(ServerMessage.Ended("booted"))
        tuneInAgain()
        receive(ServerMessage.Ended("station_removed"))
        assertThat(notices).containsExactly("You were removed from this session", "This station is off the air").inOrder()
        assertThat(restores).hasSize(2)
    }

    @Test fun `the host hears that the session ended, or that their station is off the air`() = runTest {
        val notices = notices()
        val restores = restores()
        coEvery { api.createSession(STATION, "Rawn", KEY) } returns created()
        session()
        val reasons = listOf("host_ended", "paused", "closed", "replaced", "host_left", "station_removed")
        for (reason in reasons) {
            goLiveAgain()
            receive(ServerMessage.Ended(reason))
            assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
        }
        assertThat(notices).containsExactlyElementsIn(List(5) { "Session ended" } + "Your station is off the air").inOrder()
        assertThat(restores).hasSize(reasons.size)
        coVerify(exactly = reasons.size) { api.createSession(any(), any(), any()) } // none of these goes live again
    }

    @Test fun `a room's close after the welcome reads as its end`() = runTest {
        val notices = notices()
        session()
        tuneInAgain()
        connection.incoming.trySend(RoomEvent.Closed(CloseReason.ENDED)); runCurrent()
        assertThat(notices).containsExactly("Maya's session ended")
    }

    @Test fun `a full session says so before letting anyone in`() = runTest {
        val notices = notices()
        tuneIn()
        connection.incoming.trySend(RoomEvent.Closed(CloseReason.FULL)); runCurrent()
        assertThat(notices).containsExactly("This session is full")
        assertThat(controller.active.value).isFalse()
    }

    @Test fun `a session's host can't hand it over - their Leave ends it, and Make host is never sent`() = runTest {
        val notices = notices()
        goLive()
        controller.send(Command.MakeHost("h")); runCurrent()
        controller.send(Command.Leave); runCurrent()
        assertThat(connection.sent.filterIsInstance<ClientMessage.MakeHost>()).isEmpty()
        assertThat(connection.sent).contains(ClientMessage.End)
        assertThat(notices).containsExactly("Session ended")
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
    }

    @Test fun `a listener's Leave just leaves`() = runTest {
        val restores = restores()
        listening()
        controller.send(Command.Leave); runCurrent()
        assertThat(connection.sent).doesNotContain(ClientMessage.End)
        assertThat(connection.closed).isTrue()
        assertThat(restores).hasSize(1)
    }

    @Test fun `at 12 hours a listener moves to the station's newer session quietly, the user's queue still aside`() = runTest {
        val notices = notices()
        val restores = restores()
        // Nothing listed yet, then the old room still listed, then the new one.
        val answers = ArrayDeque(listOf(null, "K7QA2PXM", "M8RB3QYN"))
        liveRoom = { answers.removeFirstOrNull() }
        listening()
        receive(ServerMessage.Ended("max_age"))
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Connecting(hosting = false, station = STATION))
        assertThat(controller.active.value).isTrue()
        assertThat(player.calls.last()).isEqualTo("pause")
        assertThat(player.intent).isFalse()
        advanceTimeBy(6_001); runCurrent() // asks at once, at 3 s and at 6 s
        assertThat(liveRoomAsks).isEqualTo(3)
        assertThat(connections.map { it.first }).containsExactly("K7QA2PXM", "M8RB3QYN").inOrder()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", v = 3, communityKey = KEY))
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok2", state()))
        assertThat(inRoom().code).isEqualTo("M8RB3QYN")
        assertThat(inRoom().url).isEqualTo("https://stashfm.app/s/$STATION")
        assertThat(notices).isEmpty()
        assertThat(restores).isEmpty()
        assertThat(player.calls.count { it == "save" }).isEqualTo(1) // the session's song is never saved as the user's
        assertThat(player.calls).doesNotContain("exit")
    }

    @Test fun `replaced, a listener moves at once when the newer session is already listed`() = runTest {
        liveRoom = { "M8RB3QYN" }
        listening()
        receive(ServerMessage.Ended("replaced"))
        assertThat(connections.map { it.first }).containsExactly("K7QA2PXM", "M8RB3QYN").inOrder()
        assertThat(liveRoomAsks).isEqualTo(1)
    }

    @Test fun `a move that finds no newer session in 30 s ends it, with the user's queue back`() = runTest {
        val notices = notices()
        val restores = restores()
        listening()
        receive(ServerMessage.Ended("replaced"))
        advanceTimeBy(29_999); runCurrent()
        assertThat(notices).isEmpty()
        advanceTimeBy(2); runCurrent()
        assertThat(liveRoomAsks).isEqualTo(11)
        assertThat(notices).containsExactly("Maya's session ended")
        assertThat(restores).hasSize(1)
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
        assertThat(connections).hasSize(1)
    }

    @Test fun `when the host's phone is gone, one look - a newer session is followed, none means it ended`() = runTest {
        val notices = notices()
        liveRoom = { "M8RB3QYN" }
        session()
        tuneInAgain()
        receive(ServerMessage.Ended("host_left"))
        assertThat(connections.last().first).isEqualTo("M8RB3QYN")
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok2", state()))
        liveRoom = { "M8RB3QYN" } // still this one listed: nothing newer
        receive(ServerMessage.Ended("host_left"))
        advanceTimeBy(30_000); runCurrent()
        assertThat(liveRoomAsks).isEqualTo(2)
        assertThat(notices).containsExactly("Maya's session ended")
    }

    @Test fun `leaving while moving stops looking and puts the user's queue back`() = runTest {
        val notices = notices()
        val restores = restores()
        listening()
        receive(ServerMessage.Ended("max_age"))
        controller.send(Command.Leave); runCurrent()
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
        assertThat(restores).hasSize(1)
        advanceTimeBy(30_001); runCurrent()
        assertThat(liveRoomAsks).isEqualTo(1)
        assertThat(notices).isEmpty()
    }

    @Test fun `a tune-in while moving starts from the user's queue`() = runTest {
        val restores = restores()
        listening()
        receive(ServerMessage.Ended("max_age"))
        controller.send(Command.TuneIn("P9SC4RZQ", STATION)); runCurrent()
        assertThat(restores).hasSize(1)
        assertThat(connections.last().first).isEqualTo("P9SC4RZQ")
        advanceTimeBy(30_001); runCurrent()
        assertThat(liveRoomAsks).isEqualTo(1) // the move stopped
    }

    @Test fun `at 12 hours a playing host goes live again at once, carrying the song, its place and the queue`() = runTest {
        val notices = notices()
        val restores = restores()
        coEvery { api.createSession(STATION, "Rawn", KEY) } returnsMany listOf(created("K7QA2PXM"), created("M8RB3QYN"))
        session()
        goLiveAgain(state(host = "me", timeline = RoomTimeline(10_000, 0, true), queue = listOf(next.copy(addedBy = "h"))))
        player.positionMs = 42_000
        receive(ServerMessage.Ended("max_age"))
        coVerify(exactly = 2) { api.createSession(STATION, "Rawn", KEY) }
        assertThat(connections.map { it.first }).containsExactly("K7QA2PXM", "M8RB3QYN").inOrder()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", hostKey = "HOSTKEY", v = 3))
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok2", state(host = "me", track = null, key = 0)))
        val load = connection.sent.filterIsInstance<ClientMessage.Load>().single()
        assertThat(load.track).isEqualTo(track)
        assertThat(load.positionMs).isEqualTo(42_000)
        assertThat(load.queue).containsExactly(next) // its adders were the old room's members
        assertThat(inRoom().isHost).isTrue()
        assertThat(notices).isEmpty()
        assertThat(restores).isEmpty()
        assertThat(player.calls.count { it == "save" }).isEqualTo(1)
    }

    @Test fun `at 12 hours a paused host's session just ends`() = runTest {
        val notices = notices()
        coEvery { api.createSession(STATION, "Rawn", KEY) } returns created()
        session()
        goLiveAgain(state(host = "me", timeline = RoomTimeline(10_000, 0, false)))
        receive(ServerMessage.Ended("max_age"))
        assertThat(notices).containsExactly("Session ended")
        coVerify(exactly = 1) { api.createSession(any(), any(), any()) }
    }

    @Test fun `a host that can't go live again says the session ended`() = runTest {
        val notices = notices()
        val restores = restores()
        coEvery { api.createSession(STATION, "Rawn", KEY) } returnsMany listOf(created(), CommunityResult.Failed("timeout"))
        session()
        goLiveAgain()
        receive(ServerMessage.Ended("max_age"))
        assertThat(notices).containsExactly("Session ended")
        assertThat(restores).hasSize(1)
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
    }

    @Test fun `a private room's end reads as before, whatever the reason`() = runTest {
        val notices = notices()
        session()
        controller.send(Command.Join("ABCDEFGH")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(session = null).copy(v = 2)))
        receive(ServerMessage.Ended("max_age"))
        assertThat(notices).containsExactly("Session ended")
        assertThat(liveRoomAsks).isEqualTo(0)
    }

    // ── A listener's time toward the vote (task 13) ───────────────────────────

    @Test fun `a listener's time counts only while on air, every 15 s and when the session ends`() = runTest {
        listening(state(timeline = RoomTimeline(0, 0, true)))
        player.events!!.onReady(); runCurrent() // on air from 0 s
        advanceTimeBy(15_000); runCurrent()
        assertThat(listened).containsExactly(STATION to 15_000L)
        player.interceptor!!.onPause(); runCurrent() // paused here at 15 s
        advanceTimeBy(15_000); runCurrent() // the report at 30 s has nothing to give
        assertThat(listened).hasSize(1)
        controller.rejoin(); runCurrent() // on air again at 30 s
        advanceTimeBy(5_000); runCurrent()
        controller.send(Command.Leave); runCurrent() // the last 5 s, as it ends
        assertThat(listened).containsExactly(STATION to 15_000L, STATION to 5_000L).inOrder()
    }

    @Test fun `time while reconnecting doesn't count`() = runTest {
        listening(state(timeline = RoomTimeline(0, 0, true)))
        player.events!!.onReady(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertThat(listened).containsExactly(STATION to 5_000L)
    }

    @Test fun `a host's time never counts, nor anything in a private room`() = runTest {
        coEvery { api.createSession(STATION, "Rawn", KEY) } returns created()
        session()
        goLiveAgain()
        player.events!!.onReady(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        controller.send(Command.Leave); runCurrent()
        controller.send(Command.Join("ABCDEFGH")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(timeline = RoomTimeline(0, 0, true), session = null).copy(v = 2)))
        player.events!!.onReady(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        controller.send(Command.Leave); runCurrent()
        assertThat(listened).isEmpty()
    }

    @Test fun `a move gives what was counted, and the newer session counts on`() = runTest {
        liveRoom = { "M8RB3QYN" }
        listening(state(timeline = RoomTimeline(0, 0, true)))
        player.events!!.onReady(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        receive(ServerMessage.Ended("replaced"))
        assertThat(listened).containsExactly(STATION to 10_000L)
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok2", state(timeline = RoomTimeline(0, 0, true))))
        player.events!!.onReady(); runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertThat(listened).containsExactly(STATION to 10_000L, STATION to 15_000L).inOrder()
    }

    // ── The DJ: requests answered, Auto-accept, co-hosts, boot, presence, a co-host's Skip (step 3) ───────────

    inline fun <reified T : ClientMessage> sent(): List<T> = connection.sent.filterIsInstance<T>()

    @Test fun `the host answers a request - play next, add to queue, or pass`() = runTest {
        goLive()
        controller.send(Command.AnswerRequest("r1", ListenTogetherController.RequestAnswer.NEXT)); runCurrent()
        controller.send(Command.AnswerRequest("r2", ListenTogetherController.RequestAnswer.QUEUE)); runCurrent()
        controller.send(Command.AnswerRequest("r3", ListenTogetherController.RequestAnswer.PASS)); runCurrent()
        assertThat(sent<ClientMessage.RequestAction>()).containsExactly(
            ClientMessage.RequestAction("r1", "next"), ClientMessage.RequestAction("r2", "queue"), ClientMessage.RequestAction("r3", "pass"),
        ).inOrder()
    }

    @Test fun `a co-host answers requests too, a listener never`() = runTest {
        listening()
        controller.send(Command.AnswerRequest("r1", ListenTogetherController.RequestAnswer.NEXT)); runCurrent()
        assertThat(sent<ClientMessage.RequestAction>()).isEmpty()
        receive(ServerMessage.Session(STATION, listeners = 1, cohosts = listOf("me")))
        controller.send(Command.AnswerRequest("r1", ListenTogetherController.RequestAnswer.NEXT)); runCurrent()
        assertThat(sent<ClientMessage.RequestAction>()).containsExactly(ClientMessage.RequestAction("r1", "next"))
    }

    @Test fun `Auto-accept, co-hosts and boot are the host's alone`() = runTest {
        listening()
        receive(ServerMessage.Session(STATION, listeners = 1, cohosts = listOf("me")))
        controller.send(Command.SetAutoAccept(true)); runCurrent()
        controller.send(Command.SetCohost("x", true)); runCurrent()
        controller.send(Command.Boot("x")); runCurrent()
        assertThat(sent<ClientMessage.AutoAccept>()).isEmpty()
        assertThat(sent<ClientMessage.Cohost>()).isEmpty()
        assertThat(sent<ClientMessage.Boot>()).isEmpty()
    }

    @Test fun `the host turns Auto-accept on and off, and makes and unmakes a co-host`() = runTest {
        goLive()
        controller.send(Command.SetAutoAccept(true)); runCurrent()
        controller.send(Command.SetAutoAccept(false)); runCurrent()
        controller.send(Command.SetCohost("x", true)); runCurrent()
        controller.send(Command.SetCohost("x", false)); runCurrent()
        assertThat(sent<ClientMessage.AutoAccept>()).containsExactly(ClientMessage.AutoAccept(true), ClientMessage.AutoAccept(false)).inOrder()
        assertThat(sent<ClientMessage.Cohost>()).containsExactly(ClientMessage.Cohost("x", true), ClientMessage.Cohost("x", false)).inOrder()
    }

    @Test fun `booting a listener is one message, a co-host stops being one first, and the host never boots themselves`() = runTest {
        goLive()
        receive(ServerMessage.Session(STATION, listeners = 2, cohosts = listOf("c")))
        controller.send(Command.Boot("x")); runCurrent()
        controller.send(Command.Boot("c")); runCurrent()
        controller.send(Command.Boot("me")); runCurrent()
        assertThat(sent<ClientMessage.Boot>()).containsExactly(ClientMessage.Boot("x"), ClientMessage.Boot("c")).inOrder()
        assertThat(sent<ClientMessage.Cohost>()).containsExactly(ClientMessage.Cohost("c", false))
        assertThat(connection.sent.indexOf(ClientMessage.Cohost("c", false))).isLessThan(connection.sent.indexOf(ClientMessage.Boot("c")))
    }

    @Test fun `the room's refusals are said in words`() = runTest {
        val notices = notices()
        goLive()
        receive(ServerMessage.Refused("cohost", "max_cohosts"))
        receive(ServerMessage.Refused("request", "queue_full"))
        receive(ServerMessage.Refused("something", "new")) // one this app doesn't know: nothing
        assertThat(notices).containsExactly("A session can have 3 co-hosts", "The queue is full. That request is still waiting.").inOrder()
    }

    @Test fun `a co-host's Skip goes to the host, a listener's nowhere`() = runTest {
        val notices = notices()
        listening()
        player.interceptor!!.onNext(); runCurrent()
        assertThat(sent<ClientMessage.Command>()).isEmpty()
        receive(ServerMessage.Session(STATION, listeners = 1, cohosts = listOf("me")))
        player.interceptor!!.onNext(); runCurrent()
        assertThat(sent<ClientMessage.Command>()).containsExactly(ClientMessage.Command("skip"))
        receive(ServerMessage.Refused("skip", "host_reconnecting"))
        assertThat(notices).containsExactly("Maya is reconnecting. Try again in a moment.")
    }

    @Test fun `the host's phone skips for a co-host, but not a song that has already changed`() = runTest {
        goLive()
        receive(ServerMessage.StateSync(state(host = "me", key = 1, timeline = RoomTimeline(0, 0, true), queue = listOf(next))))
        val loadsBefore = sent<ClientMessage.Load>().size
        receive(ServerMessage.Command("skip", from = "c", trackKey = 9)) // pressed on another song
        assertThat(sent<ClientMessage.Load>()).hasSize(loadsBefore)
        receive(ServerMessage.Command("skip", from = "c", trackKey = 1))
        val load = sent<ClientMessage.Load>().last()
        assertThat(sent<ClientMessage.Load>()).hasSize(loadsBefore + 1)
        assertThat(load.track).isEqualTo(next)
        assertThat(load.why).isEqualTo("skip")
    }

    @Test fun `a listener's phone ignores a skip command`() = runTest {
        listening(state(queue = listOf(next)))
        receive(ServerMessage.Command("skip", from = "c", trackKey = 1))
        assertThat(sent<ClientMessage.Load>()).isEmpty()
    }

    @Test fun `the host says away and back, once each, and nobody else says anything`() = runTest {
        goLive()
        assertThat(sent<ClientMessage.Presence>()).isEmpty() // in the booth, as the room assumes
        controller.setInBooth(false); runCurrent()
        controller.setInBooth(false); runCurrent()
        controller.setInBooth(true); runCurrent()
        assertThat(sent<ClientMessage.Presence>()).containsExactly(ClientMessage.Presence("away"), ClientMessage.Presence("booth")).inOrder()
    }

    @Test fun `a listener's presence is never sent`() = runTest {
        listening()
        controller.setInBooth(false); runCurrent()
        assertThat(sent<ClientMessage.Presence>()).isEmpty()
    }

    @Test fun `a host who went away before going live says so at the welcome`() = runTest {
        session()
        controller.setInBooth(false); runCurrent()
        coEvery { api.createSession(STATION, "Rawn", KEY) } returns created()
        controller.send(Command.GoLive(STATION, "Rawn")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(host = "me", track = null, key = 0)))
        assertThat(sent<ClientMessage.Presence>()).containsExactly(ClientMessage.Presence("away"))
    }

    @Test fun `a change of presence while the socket was down is said after the next welcome`() = runTest {
        goLive()
        connection.up = false
        controller.setInBooth(false); runCurrent()
        assertThat(sent<ClientMessage.Presence>()).isEmpty()
        connection.up = true
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(host = "me", track = null, key = 0)))
        assertThat(sent<ClientMessage.Presence>()).containsExactly(ClientMessage.Presence("away"))
    }

    companion object {
        const val STATION = "Ab3dEf9h"
        val KEY = "k".repeat(43)
    }
}
