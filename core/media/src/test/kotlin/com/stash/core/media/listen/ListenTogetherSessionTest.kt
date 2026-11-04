package com.stash.core.media.listen

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listen.CloseReason
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.listen.RoomConnection
import com.stash.core.data.listen.RoomEvent
import com.stash.core.data.share.ShareResult
import com.stash.core.media.PlayNext
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomPhase
import com.stash.core.model.listen.RoomState
import com.stash.core.model.listen.RoomTimeline
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ListenTogetherSessionTest {
    val track = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000, isrc = "GBBPW0100025")
    val next = SharedTrack("Xtal", "Aphex Twin")
    val third = SharedTrack("Rhubarb", "Aphex Twin")
    fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    class FakePlayer : SessionPlayer {
        val calls = mutableListOf<String>()
        var interceptor: SessionInterceptor? = null
        var queue = UserQueue(null, emptyList(), 0)
        var currentSpeed = 1f
        override var positionMs = 0L
        override var durationMs: Long? = 125_000L
        override var ended = false
        override var events: SessionPlayerEvents? = null
        override fun userQueue() = queue
        override suspend fun saveUserPosition() { calls += "save" }
        override fun enterSession(isHost: Boolean, interceptor: SessionInterceptor) {
            calls += if (isHost) "enter:host" else "enter:listener"
            this.interceptor = interceptor
        }
        override fun exitSession() { calls += "exit" }
        override fun load(item: MediaItem, positionMs: Long) { calls += "load:${item.mediaId}@$positionMs" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; this.positionMs = positionMs }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }

        /** Records changes only, so the routine setSpeed(1f) of an idle player stays out of [calls]. */
        override fun setSpeed(speed: Float) {
            if (speed != currentSpeed) calls += "speed:$speed"
            currentSpeed = speed
        }
    }

    class FakeConnection : RoomConnection {
        val sent = mutableListOf<ClientMessage>()
        val incoming = Channel<RoomEvent>(Channel.UNLIMITED)
        var closed = false
        /** False while the socket is down: send() drops the frame and says so. */
        var up = true
        var hello: (() -> ClientMessage.Hello)? = null
        var scope: CoroutineScope? = null
        /** RoomClient aborts its socket when its scope is cancelled before close(), losing a frame sent just before. */
        var closedAfterScopeCancelled = false
        override val events: Flow<RoomEvent> = incoming.receiveAsFlow()
        override fun send(message: ClientMessage): Boolean { if (up) sent += message; return up }
        override fun close() { closed = true; if (scope?.isActive == false) closedAfterScopeCancelled = true }
    }

    inner class FakeCatalog : SessionCatalog {
        val items = mutableMapOf(track to item("1"), next to item("2"), third to item("3"))
        var radio: List<SharedTrack> = emptyList()
        var radioCalls = 0
        /** When set, lookups and radio wait on it: a slow DB or network. */
        var itemGate: CompletableDeferred<Unit>? = null
        var radioGate: CompletableDeferred<Unit>? = null
        override suspend fun mediaItemFor(track: SharedTrack): MediaItem? { itemGate?.await(); return items[track] }
        override suspend fun sharedTrackFor(item: MediaItem) = items.entries.firstOrNull { it.value.mediaId == item.mediaId }?.key
        override suspend fun radioAfter(track: SharedTrack): List<SharedTrack> { radioCalls++; radioGate?.await(); return radio }
    }

    val player = FakePlayer()
    val connection = FakeConnection()
    val catalog = FakeCatalog()
    val api: RoomApiClient = mockk()
    // The real app context: the session's toasts are string resources.
    val controller = ListenTogetherController(ApplicationProvider.getApplicationContext()).apply { serviceAttached = true }
    var autoplay = false
    var guestsAddPref = true

    fun TestScope.session() = ListenTogetherSession(
        scope = backgroundScope,
        controller = controller,
        player = player,
        catalog = catalog,
        api = api,
        connector = { _, scope, hello -> connection.hello = hello; connection.scope = scope; connection },
        autoplayRadio = { autoplay },
        displayName = { "Rawn" },
        clock = { testScheduler.currentTime },
        guestsAddPreference = { guestsAddPref },
    ).also { it.start() }

    /**
     * The room as a phone sees it. [v] 1 is a Worker from before guest adds; with 2, [guestsAdd] is the room's
     * effective switch, [qv] its queue version and [hostV] what the host's app speaks.
     */
    fun state(
        host: String? = "h",
        track: SharedTrack? = this.track,
        key: Int = 1,
        timeline: RoomTimeline = RoomTimeline(0, 0, false),
        queue: List<SharedTrack> = emptyList(),
        v: Int = 1,
        guestsAdd: Boolean = false,
        qv: Int? = null,
        hostV: Int? = null,
    ) = RoomState(
        rev = 1, host = host, track = track, trackKey = key, timeline = timeline, queue = queue,
        members = listOf(RoomMember("h", "Host", 1, "ok", v = hostV), RoomMember("me", "Me", 2, "ok", v = 2)), phase = RoomPhase(),
        v = v, guestsAdd = guestsAdd, qv = qv,
    )

    fun TestScope.receive(m: ServerMessage) { connection.incoming.trySend(RoomEvent.Message(m, receivedAt = testScheduler.currentTime)); runCurrent() }

    /** A pong with no round trip: room time = local time + 10 s from here on. */
    fun TestScope.syncClock() = receive(ServerMessage.Pong(c = testScheduler.currentTime, r = testScheduler.currentTime + 10_000))

    fun TestScope.join() {
        session()
        controller.send(Command.Join("K7QA2PXM")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
    }

    @Test fun `joining sets the user's queue aside before touching the player`() = runTest {
        join()
        assertThat(controller.active.value).isTrue()
        assertThat(player.calls.take(3)).containsExactly("save", "enter:listener", "pause").inOrder()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", v = 2, guestsAdd = true))
    }

    @Test fun `a prepared song is loaded paused and ready is reported once the player can play it`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(track = null, key = 0)))
        receive(ServerMessage.Prepare(trackKey = 1, track = track, positionMs = 0, deadlineMs = 8_000))
        assertThat(player.calls).contains("load:1@0")
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>()).isEmpty()
        player.events!!.onReady()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>()).containsExactly(ClientMessage.Status("ready", 1))
        assertThat(connection.hello!!().resumeToken).isEqualTo("tok")
    }

    @Test fun `a start time in the future starts the player exactly then`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        player.events!!.onReady()
        player.calls.clear()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 30_000, atRoomMs = 10_500, playing = true))
        assertThat(player.calls).containsExactly("pause", "seek:30000").inOrder()
        advanceTimeBy(499); runCurrent()
        assertThat(player.calls).doesNotContain("play")
        advanceTimeBy(1); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    @Test fun `joining mid-song starts on the next whole room second from where the song will be then`() = runTest {
        join(); advanceTimeBy(200); runCurrent(); syncClock() // local 200 = room 10 200
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(positionMs = 0, atRoomMs = 5_000, playing = true))))
        player.events!!.onReady()
        // the first whole second at least 1 s ahead of room 10 200 is 12 000; the song is then at 12 000 − 5 000
        assertThat(player.calls).contains("seek:7000")
        advanceTimeBy(1_799); runCurrent() // local 1 999 = room 11 999
        assertThat(player.calls).doesNotContain("play")
        advanceTimeBy(1); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    @Test fun `a drift seek resets the speed first and drift waits 2 s before acting again`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 10_500, true))))
        player.events!!.onReady()
        advanceTimeBy(500); runCurrent() // local 500 = room 10 500: play
        player.calls.clear()
        player.positionMs = 1_500 // tick at local 1 500 expects 1 000: 500 ms ahead, a speed nudge
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls).containsExactly("speed:0.97")
        player.positionMs = 9_000 // tick at local 2 500 expects 2 000: 7 s off, a seek
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls).containsExactly("speed:0.97", "speed:1.0", "seek:2000").inOrder()
        player.calls.clear()
        player.positionMs = 9_000 // still far off, but the seek is rebuffering
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls).isEmpty()
        advanceTimeBy(1_000); runCurrent() // 2 s after the seek: drift acts again
        assertThat(player.calls).containsExactly("seek:4000")
    }

    @Test fun `a reconnect keeps playing and an unchanged timeline doesn't restart the song`() = runTest {
        join(); syncClock()
        val playing = state(key = 1, timeline = RoomTimeline(0, 10_000, true))
        receive(ServerMessage.Welcome("me", "tok", playing))
        player.events!!.onReady()
        advanceTimeBy(1_000); runCurrent()
        player.calls.clear()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        assertThat((controller.state.value as ListenTogetherState.InRoom).reconnecting).isTrue()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", playing.copy(rev = 7)))
        assertThat(player.calls).isEmpty()
        assertThat((controller.state.value as ListenTogetherState.InRoom).reconnecting).isFalse()
    }

    @Test fun `a listener's seek, skip and set reach neither the room nor the player`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        val before = player.calls.toList()
        connection.sent.clear()
        player.interceptor!!.run {
            onSeek(5_000); onNext(); onPrevious(); onSet(listOf(item("2")), 0)
        }
        runCurrent()
        assertThat(connection.sent).isEmpty()
        assertThat(player.calls).isEqualTo(before)
    }

    @Test fun `a listener's add from a track menu is sent as a suggestion`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.sent.clear()
        player.interceptor!!.onAdd(listOf(item("2"))); gathered()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Suggest(next))
    }

    @Test fun `a song that can't be resolved is reported unavailable, shown and logged`() = runTest {
        catalog.items.remove(track)
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>()).containsExactly(ClientMessage.Status("unavailable", 1))
        assertThat((controller.state.value as ListenTogetherState.InRoom).unavailable).isTrue()
        assertThat(ShadowLog.getLogsForTag("ListenTogether").map { it.type }).contains(Log.WARN)
    }

    @Test fun `a player error mid-song is reported unavailable too`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        player.events!!.onError()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>().last()).isEqualTo(ClientMessage.Status("unavailable", 1))
    }

    @Test fun `leaving puts everything back`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        controller.send(Command.Leave); runCurrent()
        assertThat(player.calls.last()).isEqualTo("exit")
        assertThat(connection.closed).isTrue()
        assertThat(controller.active.value).isFalse()
        assertThat(controller.restorePending).isTrue() // PlayerRepositoryImpl clears it once the queue is back
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
    }

    @Test fun `the service shutting down leaves quietly, with no restore`() = runTest {
        val ends = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.sessionEnds.collect { ends += it } }
        val s = session()
        controller.send(Command.Join("K7QA2PXM")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        s.shutdown()
        assertThat(player.calls.last()).isEqualTo("exit")
        assertThat(connection.closed).isTrue()
        assertThat(controller.active.value).isFalse()
        assertThat(controller.restorePending).isFalse()
        assertThat(ends).isEmpty()
    }

    fun TestScope.notices(): List<String> {
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { notices += it } }
        return notices
    }

    fun statuses() = connection.sent.filterIsInstance<ClientMessage.Status>()

    @Test fun `an old song's ready before the new song loads is ignored`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        player.events!!.onReady()
        connection.sent.clear()
        catalog.itemGate = CompletableDeferred()
        receive(ServerMessage.Prepare(trackKey = 2, track = next, positionMs = 0, deadlineMs = 8_000))
        player.events!!.onReady() // still song 1 in the player
        player.events!!.onEnded()
        player.events!!.onError()
        assertThat(connection.sent).isEmpty()
        catalog.itemGate!!.complete(Unit); runCurrent()
        assertThat(player.calls.last()).isEqualTo("load:2@0")
        player.events!!.onReady()
        assertThat(statuses()).containsExactly(ClientMessage.Status("ready", 2))
    }

    @Test fun `a reconnect mid-handshake re-reports ready`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        player.events!!.onReady()
        connection.sent.clear()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        assertThat(statuses()).containsExactly(ClientMessage.Status("ready", 1))
    }

    @Test fun `a reconnect re-reports unavailable`() = runTest {
        catalog.items.remove(track)
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.sent.clear()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        assertThat(statuses()).containsExactly(ClientMessage.Status("unavailable", 1))
    }

    @Test fun `a host whose song is unavailable moves the room on`() = runTest {
        host()
        catalog.items.remove(track)
        connection.sent.clear()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        // "unavailable", so every phone can say "Skipped Avril 14th: Rawn can't play it".
        assertThat(connection.sent).containsExactly(ClientMessage.Status("unavailable", 1), ClientMessage.Load(next, 0, emptyList(), "unavailable")).inOrder()
    }

    @Test fun `a pending advance after leaving doesn't reach the next session`() = runTest {
        autoplay = true
        catalog.radio = listOf(next)
        catalog.radioGate = CompletableDeferred()
        host(upcoming = emptyList())
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        player.events!!.onEnded(); runCurrent() // waiting on the station
        controller.send(Command.Leave); runCurrent()
        controller.send(Command.Join("ZZ22ZZ22")); runCurrent()
        connection.sent.clear()
        catalog.radioGate!!.complete(Unit); runCurrent()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Load>()).isEmpty()
    }

    @Test fun `a paused timeline pauses then seeks and doesn't start`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 10_000, true))))
        player.events!!.onReady()
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
        player.calls.clear()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 20_000, atRoomMs = 11_000, playing = false))
        assertThat(player.calls).containsExactly("pause", "seek:20000").inOrder()
        advanceTimeBy(5_000); runCurrent()
        assertThat(player.calls).containsExactly("pause", "seek:20000").inOrder()
    }

    @Test fun `10 s off by over 250 ms reports drifting and back in sync reports ready`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 10_500, true))))
        player.events!!.onReady()
        advanceTimeBy(500); runCurrent() // plays at local 500, drift ticks at 1 500, 2 500 and on
        var tick = 1_500L
        repeat(11) { player.positionMs = tick; advanceTimeBy(1_000); runCurrent(); tick += 1_000 } // 500 ms ahead
        assertThat(statuses().map { it.status }).containsExactly("ready", "drifting").inOrder()
        player.positionMs = tick - 500 // exactly where the room is
        advanceTimeBy(1_000); runCurrent()
        assertThat(statuses().map { it.status }).containsExactly("ready", "drifting", "ready").inOrder()
    }

    @Test fun `make host, suggestion and reaction commands go to the room`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.sent.clear()
        controller.send(Command.MakeHost("h"))
        controller.send(Command.Suggestion("s1", add = true))
        controller.send(Command.Suggestion("s2", add = false))
        controller.send(Command.React("heart"))
        runCurrent()
        assertThat(connection.sent).containsExactly(
            ClientMessage.MakeHost("h"),
            ClientMessage.SuggestionAction("s1", "add"),
            ClientMessage.SuggestionAction("s2", "dismiss"),
            ClientMessage.React("heart"),
        ).inOrder()
    }

    @Test fun `joining a room that has already ended says so`() = runTest {
        val notices = notices()
        join()
        connection.incoming.trySend(RoomEvent.Closed(CloseReason.ENDED)); runCurrent()
        assertThat(notices).containsExactly("This session has ended")
        assertThat(controller.active.value).isFalse()
    }

    @Test fun `a room that ends mid-session says session ended`() = runTest {
        val notices = notices()
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.incoming.trySend(RoomEvent.Closed(CloseReason.ENDED)); runCurrent()
        assertThat(notices).containsExactly("Session ended")
    }

    @Test fun `a full room says so`() = runTest {
        val notices = notices()
        join()
        connection.incoming.trySend(RoomEvent.Closed(CloseReason.FULL)); runCurrent()
        assertThat(notices).containsExactly("This session is full")
        assertThat(player.calls.last()).isEqualTo("exit")
    }

    @Test fun `rejoining from another device says so here`() = runTest {
        val notices = notices()
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.incoming.trySend(RoomEvent.Closed(CloseReason.REPLACED)); runCurrent()
        assertThat(notices).containsExactly("You rejoined this session somewhere else")
    }

    fun pausedLocally() = (controller.state.value as ListenTogetherState.InRoom).pausedLocally

    @Test fun `a host's external pause pauses the room`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.events!!.onExternalPause()
        assertThat(connection.sent).containsExactly(ClientMessage.Pause)
    }

    @Test fun `a host's external pause during preparing keeps it paused when the start arrives`() = runTest {
        host(); syncClock()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        player.events!!.onReady()
        player.events!!.onExternalPause() // the room ignores this pause: it is still preparing
        connection.sent.clear(); player.calls.clear()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 42_000, atRoomMs = 11_000, playing = true))
        assertThat(connection.sent).containsExactly(ClientMessage.Pause)
        advanceTimeBy(5_000); runCurrent()
        assertThat(player.calls).doesNotContain("play")
        receive(ServerMessage.TimelineUpdate(rev = 3, trackKey = 1, positionMs = 42_000, atRoomMs = 15_000, playing = false))
        assertThat(pausedLocally()).isFalse()
    }

    @Test fun `a listener's external pause sets pausedLocally, sends nothing and cancels the start`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 10_500, true))))
        player.events!!.onReady() // starts at local 500
        connection.sent.clear()
        player.events!!.onExternalPause()
        assertThat(connection.sent).isEmpty()
        assertThat(pausedLocally()).isTrue()
        advanceTimeBy(3_000); runCurrent()
        assertThat(player.calls).doesNotContain("play")
    }

    fun TestScope.playThenPauseExternally() {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 10_000, true))))
        player.events!!.onReady()
        advanceTimeBy(1_000); runCurrent() // playing from local 1 000
        player.events!!.onExternalPause()
        player.calls.clear()
        player.positionMs = 50_000 // far off, but drift leaves a locally paused player alone
        advanceTimeBy(3_000); runCurrent() // local 4 000 = room 14 000
        assertThat(player.calls).isEmpty()
    }

    @Test fun `rejoin seeks to where the room is and plays`() = runTest {
        playThenPauseExternally()
        controller.rejoin(); runCurrent()
        // next whole room second at least 1 s ahead of 14 000 is 15 000, where the song is at 5 000
        assertThat(player.calls).contains("seek:5000")
        assertThat(pausedLocally()).isFalse()
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    @Test fun `an external resume rejoins the room`() = runTest {
        playThenPauseExternally()
        player.events!!.onExternalResume(); runCurrent()
        assertThat(player.calls).contains("seek:5000")
        assertThat(pausedLocally()).isFalse()
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    @Test fun `a listener's play from a headset or the notification rejoins the room`() = runTest {
        playThenPauseExternally()
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(player.calls).contains("seek:5000")
        assertThat(pausedLocally()).isFalse()
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
        assertThat(connection.sent).containsNoneOf(ClientMessage.Play, ClientMessage.Pause)
    }

    @Test fun `a listener's pause pauses only their own player`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 10_000, true))))
        player.events!!.onReady()
        advanceTimeBy(1_000); runCurrent()
        connection.sent.clear(); player.calls.clear()
        player.interceptor!!.onPause(); runCurrent()
        assertThat(player.calls).containsExactly("pause")
        assertThat(pausedLocally()).isTrue()
        assertThat(connection.sent.filterNot { it is ClientMessage.Ping }).isEmpty()
    }

    @Test fun `a locally paused listener loads the next song but doesn't play it, and rejoin plays it`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 10_000, true))))
        player.events!!.onReady()
        advanceTimeBy(1_000); runCurrent() // playing
        player.events!!.onExternalPause() // a call
        player.calls.clear()
        receive(ServerMessage.Prepare(2, next, 0, 8_000))
        player.events!!.onReady()
        receive(ServerMessage.TimelineUpdate(rev = 3, trackKey = 2, positionMs = 0, atRoomMs = 12_000, playing = true))
        assertThat(player.calls).contains("load:2@0")
        advanceTimeBy(5_000); runCurrent() // local 6 000 = room 16 000
        assertThat(player.calls).doesNotContain("play")
        assertThat(pausedLocally()).isTrue()
        controller.rejoin(); runCurrent()
        // next whole room second at least 1 s ahead of 16 000 is 17 000, where the song is at 5 000
        assertThat(player.calls).contains("seek:5000")
        assertThat(pausedLocally()).isFalse()
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    @Test fun `a paused host's new song keeps the room paused`() = runTest {
        host(); syncClock()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        player.events!!.onReady()
        player.events!!.onExternalPause() // a call, just as the song changes
        receive(ServerMessage.Prepare(2, next, 0, 8_000))
        player.events!!.onReady()
        connection.sent.clear(); player.calls.clear()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 2, positionMs = 0, atRoomMs = 11_000, playing = true))
        assertThat(connection.sent).containsExactly(ClientMessage.Pause)
        advanceTimeBy(5_000); runCurrent()
        assertThat(player.calls).doesNotContain("play")
        assertThat(pausedLocally()).isTrue()
    }

    @Test fun `after a call, the host's external resume sends Play`() = runTest {
        host(); syncClock()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        player.events!!.onReady()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 42_000, atRoomMs = 11_000, playing = true))
        advanceTimeBy(2_000); runCurrent()
        player.events!!.onExternalPause()
        receive(ServerMessage.TimelineUpdate(rev = 3, trackKey = 1, positionMs = 43_000, atRoomMs = 12_000, playing = false))
        connection.sent.clear(); player.calls.clear()
        player.events!!.onExternalResume(); runCurrent()
        assertThat(player.calls).containsExactly("pause") // the room's reply starts it
        assertThat(connection.sent.filterNot { it is ClientMessage.Ping }).containsExactly(ClientMessage.Play)
        assertThat(pausedLocally()).isFalse()
    }

    @Test fun `when the room ends everyone gets their own music back and a notice`() = runTest {
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { notices += it } }
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        receive(ServerMessage.Ended("host_ended"))
        assertThat(player.calls.last()).isEqualTo("exit")
        assertThat(controller.active.value).isFalse()
        assertThat(notices).containsExactly("Session ended")
    }

    fun TestScope.host(
        upcoming: List<MediaItem> = listOf(item("2")),
        serverUrl: String = "https://x/l/K7QA2PXM",
        v: Int = 1,
        qv: Int? = null,
    ) {
        coEvery { api.create("Rawn") } returns ShareResult.Ok(RoomApiClient.Created("K7QA2PXM", "KEY", serverUrl))
        player.queue = UserQueue(current = item("1"), upcoming = upcoming, positionMs = 40_000)
        player.positionMs = 42_000 // the song played on while the room was being created
        session()
        controller.send(Command.Host); runCurrent()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", hostKey = "KEY", v = 2, guestsAdd = true))
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = null, key = 0, v = v, qv = qv, hostV = 2)))
    }

    @Test fun `hosting sets the queue aside and sends the user's song and queue to the new room`() = runTest {
        host()
        assertThat(player.calls.take(3)).containsExactly("save", "enter:host", "pause").inOrder()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Load>()).containsExactly(ClientMessage.Load(track, 42_000, listOf(next)))
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", resumeToken = "tok", v = 2, guestsAdd = true))
        assertThat((controller.state.value as ListenTogetherState.InRoom).url).isEqualTo(ShareLinks.roomUrl("K7QA2PXM"))
    }

    @Test fun `the host's invite is always a stashfm-app link, whichever host made the room`() = runTest {
        // When stashfm.app can't be reached the room is made on the old workers.dev host, which answers with its own url.
        host(serverUrl = "https://stash-share.rawnaldclark.workers.dev/l/K7QA2PXM")
        assertThat((controller.state.value as ListenTogetherState.InRoom).url).isEqualTo("https://stashfm.app/l/K7QA2PXM")
    }

    @Test fun `a room that can't be created leaves everything alone`() = runTest {
        coEvery { api.create(any()) } returns ShareResult.Failed("offline")
        session()
        controller.send(Command.Host); runCurrent()
        assertThat(player.calls).isEmpty()
        assertThat(controller.active.value).isFalse()
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
    }

    @Test fun `the host's own controls go to the room and its player waits for the reply`() = runTest {
        host()
        player.calls.clear(); connection.sent.clear()
        player.interceptor!!.run { onPause(); onPlay(); onSeek(90_000) }
        assertThat(connection.sent).containsExactly(ClientMessage.Pause, ClientMessage.Play, ClientMessage.Seek(90_000)).inOrder()
        assertThat(player.calls).isEmpty()
    }

    @Test fun `when a song ends the host loads the next one from its queue`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, emptyList(), "end"))
    }

    @Test fun `with the queue empty and autoplay radio on the host starts a station`() = runTest {
        autoplay = true
        catalog.radio = listOf(next, third)
        host(upcoming = emptyList())
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, listOf(third), "radio"))
    }

    @Test fun `a station that can't be built isn't retried for the same song, and the room pauses`() = runTest {
        autoplay = true
        host(upcoming = emptyList())
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        receive(ServerMessage.TimelineUpdate(2, 1, 42_000, 0, true))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        player.events!!.onEnded(); runCurrent()
        assertThat(catalog.radioCalls).isEqualTo(1)
        assertThat(connection.sent.filterIsInstance<ClientMessage.Pause>()).isNotEmpty()
    }

    @Test fun `next says skip and previous says back, so other phones can name who did it`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.interceptor!!.onNext(); runCurrent()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Load>().single().why).isEqualTo("skip")
        player.positionMs = 1_000
        player.interceptor!!.onPrevious(); runCurrent()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Load>().last().why).isEqualTo("back")
    }

    fun TestScope.events(): MutableList<SessionEvent> {
        val out = mutableListOf<SessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { out += it } }
        return out
    }

    @Test fun `a newcomer is named once, someone gone 15 s has left, and a blip that comes back says nothing`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state()))
        val events = events()
        val h = RoomMember("h", "Host", 1); val me = RoomMember("me", "Me", 2); val a = RoomMember("a", "Ana", 3)
        receive(ServerMessage.Members(listOf(h, me, a)))
        assertThat(events).containsExactly(SessionEvent(SessionEvent.Kind.JOINED, "a", "Ana"))
        receive(ServerMessage.Members(listOf(h, me))) // Ana drops
        advanceTimeBy(10_000); runCurrent()
        receive(ServerMessage.Members(listOf(h, me, a))) // and is back within the grace period
        advanceTimeBy(20_000); runCurrent()
        assertThat(events).hasSize(1)
        receive(ServerMessage.Members(listOf(h, me)))
        advanceTimeBy(15_000); runCurrent()
        assertThat(events.last()).isEqualTo(SessionEvent(SessionEvent.Kind.LEFT, "a", "Ana"))
        // Gone from the room, still named for her picks.
        assertThat((controller.state.value as ListenTogetherState.InRoom).names["a"]).isEqualTo("Ana")
    }

    @Test fun `other people's pause, resume, skip and back are named, but your own and a natural end are not`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(timeline = RoomTimeline(0, 0, true))))
        val events = events()
        receive(ServerMessage.TimelineUpdate(2, 1, 5_000, 0, playing = false, by = "h"))
        receive(ServerMessage.TimelineUpdate(3, 1, 5_000, 0, playing = true, by = "h"))
        receive(ServerMessage.TimelineUpdate(4, 1, 6_000, 0, playing = false, by = "me"))
        receive(ServerMessage.TimelineUpdate(5, 1, 6_000, 0, playing = false, by = "h")) // a seek while paused
        receive(ServerMessage.Prepare(2, next, 0, 8_000, by = "h", why = "skip"))
        receive(ServerMessage.Prepare(3, third, 0, 8_000, by = "h", why = "back"))
        receive(ServerMessage.Prepare(4, next, 0, 8_000, by = "h", why = "end"))
        assertThat(events.map { it.kind }).containsExactly(
            SessionEvent.Kind.PAUSED, SessionEvent.Kind.RESUMED, SessionEvent.Kind.SKIPPED, SessionEvent.Kind.WENT_BACK,
        ).inOrder()
        assertThat(events.first().name).isEqualTo("Host")
    }

    @Test fun `a new host is named, including when it's you`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state()))
        val events = events()
        receive(ServerMessage.StateSync(state(host = "me")))
        assertThat(events).containsExactly(SessionEvent(SessionEvent.Kind.HOSTING, "me", "Me"))
    }

    @Test fun `a listener's Up next follows every queue change in the room`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(queue = listOf(next))))
        receive(ServerMessage.QueueUpdate(listOf(third.copy(addedBy = "h"), next)))
        val room = controller.state.value as ListenTogetherState.InRoom
        assertThat(room.queue.map { it.title }).containsExactly("Rhubarb", "Xtal").inOrder()
        assertThat(room.queue.first().addedBy).isEqualTo("h")
    }

    @Test fun `the room's song and queue reach the UI, with who added them`() = runTest {
        join()
        val picked = next.copy(addedBy = "a")
        receive(ServerMessage.Welcome("me", "tok", state(queue = listOf(picked))))
        val room = controller.state.value as ListenTogetherState.InRoom
        assertThat(room.track).isEqualTo(track)
        assertThat(room.queue.single().addedBy).isEqualTo("a")
    }

    @Test fun `previous restarts the song once it is more than 3 s in`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.positionMs = 10_000
        player.interceptor!!.onPrevious()
        assertThat(connection.sent).containsExactly(ClientMessage.Seek(0))
    }

    @Test fun `the host's add from a track menu joins the room's queue`() = runTest {
        host()
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { notices += it } }
        connection.sent.clear()
        player.interceptor!!.onAdd(listOf(item("3"))); gathered()
        // An older Worker: the host's song joins the end of the queue, as before guest adds.
        assertThat(sentNoPings()).containsExactly(ClientMessage.Queue(listOf(next, third)))
        // The track menu stays quiet in a session, so the session confirms the add (device test 2026-09-25).
        assertThat(notices).containsExactly("Added to the session queue")
    }

    @Test fun `the host's Up next edit replaces the room's queue and shows at once`() = runTest {
        host()
        connection.sent.clear()
        controller.send(Command.SetQueue(listOf(third, next))); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Queue(listOf(third, next)))
        assertThat((controller.state.value as ListenTogetherState.InRoom).queue).containsExactly(third, next).inOrder()
    }

    @Test fun `a listener's Up next edit goes nowhere`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(queue = listOf(next))))
        connection.sent.clear()
        controller.send(Command.SetQueue(emptyList())); runCurrent()
        assertThat(connection.sent).isEmpty()
        assertThat((controller.state.value as ListenTogetherState.InRoom).queue).containsExactly(next)
    }

    @Test fun `tapping a playlist while hosting plays it for everyone`() = runTest {
        host()
        connection.sent.clear()
        player.interceptor!!.onSet(listOf(item("2"), item("3")), 0); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, listOf(third), "pick"))
    }

    @Test fun `a host who leaves hands the room to the longest-joined listener`() = runTest {
        host()
        receive(ServerMessage.Members(listOf(RoomMember("h", "Host", 1), RoomMember("a", "A", 5), RoomMember("b", "B", 3))))
        controller.send(Command.Leave); runCurrent()
        assertThat(connection.sent).contains(ClientMessage.MakeHost("b"))
        assertThat(connection.closed).isTrue()
        assertThat(connection.closedAfterScopeCancelled).isFalse()
    }

    @Test fun `end session tells the room and restores the host's own music`() = runTest {
        host()
        controller.send(Command.End); runCurrent()
        assertThat(connection.sent).contains(ClientMessage.End)
        // Device test 2026-09-25: cancelling first aborted the socket, so End never left and listeners played on.
        assertThat(connection.closedAfterScopeCancelled).isFalse()
        assertThat(player.calls.last()).isEqualTo("exit")
        assertThat(controller.active.value).isFalse()
    }

    @Test fun `becoming host through a state message switches the player to the host role`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        receive(ServerMessage.StateSync(state(host = "me", key = 1)))
        assertThat(player.calls).contains("enter:host")
        assertThat((controller.state.value as ListenTogetherState.InRoom).isHost).isTrue()
    }

    fun loads() = connection.sent.filterIsInstance<ClientMessage.Load>()

    @Test fun `a host's load while the socket is down is sent once the room welcomes it back`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.up = false
        player.events!!.onEnded(); runCurrent() // the Load is dropped with the socket
        connection.up = true
        connection.sent.clear()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", key = 1)))
        assertThat(loads()).containsExactly(ClientMessage.Load(next, 0, emptyList(), "end"))
    }

    @Test fun `the first load carries where the song is when the room welcomes the host`() = runTest {
        coEvery { api.create("Rawn") } returns ShareResult.Ok(RoomApiClient.Created("K7QA2PXM", "KEY", "https://x/l/K7QA2PXM"))
        player.queue = UserQueue(current = item("1"), upcoming = listOf(item("2")), positionMs = 40_000)
        player.positionMs = 42_000
        session()
        controller.send(Command.Host); runCurrent()
        player.positionMs = 43_500 // the handshake took a moment
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = null, key = 0)))
        assertThat(loads()).containsExactly(ClientMessage.Load(track, 43_500, listOf(next)))
    }

    @Test fun `a listener promoted to host after the song ended moves the room on`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, queue = listOf(next))))
        player.ended = true
        connection.sent.clear()
        receive(ServerMessage.StateSync(state(host = "me", key = 1, queue = listOf(next))))
        assertThat(loads()).containsExactly(ClientMessage.Load(next, 0, emptyList(), "end"))
    }

    @Test fun `the host's play on an ended song moves the room on instead of sending play`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        player.ended = true
        connection.sent.clear()
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, emptyList(), "end"))
    }

    @Test fun `a host's pause during preparing is sent again when the start arrives`() = runTest {
        host(); syncClock()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        player.events!!.onReady()
        player.interceptor!!.onPause() // the room ignores this pause while it is preparing
        connection.sent.clear(); player.calls.clear()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 42_000, atRoomMs = 11_000, playing = true))
        assertThat(connection.sent).containsExactly(ClientMessage.Pause)
        advanceTimeBy(5_000); runCurrent()
        assertThat(player.calls).doesNotContain("play")
    }

    @Test fun `pings carry time since the session started, not device uptime, and still sync the clock`() = runTest {
        advanceTimeBy(3_600_000); runCurrent() // the phone has been up an hour
        join()
        val ping = connection.sent.filterIsInstance<ClientMessage.Ping>().first()
        assertThat(ping.c).isEqualTo(0)
        advanceTimeBy(100); runCurrent()
        // a 100 ms round trip: halfway was local 3 600 050, and the room said 3 610 050 then (room = local + 10 s)
        receive(ServerMessage.Pong(c = ping.c, r = 3_610_050))
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 3_610_600, true))))
        player.events!!.onReady()
        advanceTimeBy(499); runCurrent()
        assertThat(player.calls).doesNotContain("play")
        advanceTimeBy(1); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    // ── Guests add songs (spec 2026-11-04) ───────────────────────────────────

    /** One quiet window: the session sends what it gathered once 400 ms pass with nothing new. */
    fun TestScope.gathered() { advanceTimeBy(400); runCurrent() }
    /** What went to the room, without the session's clock pings (5 in the first second). */
    fun sentNoPings() = connection.sent.filterNot { it is ClientMessage.Ping }
    fun playNext(id: String): MediaItem = PlayNext.tag(item(id))
    fun adds() = connection.sent.filterIsInstance<ClientMessage.Add>()

    /** A guest in a room that knows guest adds, at queue version 3; [guestsAdd] is the room's effective switch. */
    fun TestScope.joinV2(guestsAdd: Boolean = true, queue: List<SharedTrack> = emptyList(), hostV: Int? = 2) {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, queue = queue, v = 2, guestsAdd = guestsAdd, qv = 3, hostV = hostV)))
        connection.sent.clear()
    }

    /** [n] songs the catalog knows, as items "s0", "s1", … */
    fun songs(n: Int): List<SharedTrack> = List(n) { SharedTrack("S$it", "A") }.onEachIndexed { i, t -> catalog.items[t] = item("s$i") }

    @Test fun `hello says this app knows guest adds and carries the remembered switch`() = runTest {
        guestsAddPref = false
        join()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", v = 2, guestsAdd = false))
    }

    @Test fun `a guest's Play next and Add to queue go to the room as adds of their own kind`() = runTest {
        val notices = notices()
        joinV2()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        player.interceptor!!.onAdd(listOf(item("3"))); gathered()
        assertThat(adds()).containsExactly(
            ClientMessage.Add(listOf(next), next = true),
            ClientMessage.Add(listOf(third), next = false),
        ).inOrder()
        assertThat(notices).containsExactly("Playing next in the session", "Added to the session queue").inOrder()
    }

    @Test fun `Play next and Add to queue tapped together keep their order and kinds`() = runTest {
        joinV2()
        player.interceptor!!.onAdd(listOf(playNext("2"))); advanceTimeBy(50); runCurrent()
        player.interceptor!!.onAdd(listOf(item("3"))); advanceTimeBy(50); runCurrent()
        player.interceptor!!.onAdd(listOf(playNext("1"))); gathered(); gathered()
        assertThat(adds()).containsExactly(
            ClientMessage.Add(listOf(next), next = true),
            ClientMessage.Add(listOf(third), next = false),
            ClientMessage.Add(listOf(track), next = true),
        ).inOrder()
    }

    @Test fun `a guest's whole Play selected next is one message, stopped at 20 waiting`() = runTest {
        val many = songs(25)
        val notices = notices()
        joinV2()
        // PlayerRepositoryImpl calls Play next once per song, a few ms apart: 25 frames closed the socket before.
        repeat(25) { i -> player.interceptor!!.onAdd(listOf(playNext("s$i"))); advanceTimeBy(30); runCurrent() }
        advanceTimeBy(2_000); runCurrent()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Add(many.take(20), next = true))
        assertThat(notices).containsExactly("You can have 20 songs waiting. Add more as they play.")
    }

    @Test fun `the host's selection is one message with every song`() = runTest {
        val many = songs(25)
        host(v = 2, qv = 0)
        connection.sent.clear()
        repeat(25) { i -> player.interceptor!!.onAdd(listOf(playNext("s$i"))); advanceTimeBy(30); runCurrent() }
        advanceTimeBy(2_000); runCurrent()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Add(many, next = true))
    }

    @Test fun `a guest with 19 songs waiting can add one more`() = runTest {
        val notices = notices()
        joinV2(queue = List(19) { SharedTrack("M$it", "A", addedBy = "me") } + third.copy(addedBy = "h"))
        player.interceptor!!.onAdd(listOf(playNext("2"), playNext("1"))); gathered()
        assertThat(adds()).containsExactly(ClientMessage.Add(listOf(next), next = true))
        assertThat(notices).containsExactly("You can have 20 songs waiting. Add more as they play.")
    }

    @Test fun `with adds off a guest's songs still leave as one add, which the room turns into suggestions`() = runTest {
        val notices = notices()
        joinV2(guestsAdd = false)
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        assertThat(adds()).containsExactly(ClientMessage.Add(listOf(next), next = true))
        assertThat(notices).containsExactly("Suggested to Host")
        assertThat((controller.state.value as ListenTogetherState.InRoom).guestsAdd).isFalse()
    }

    @Test fun `an older Worker gets at most 3 suggestions in one go, and the toast names the host`() = runTest {
        val fourth = SharedTrack("Four", "A").also { catalog.items[it] = item("4") }
        val notices = notices()
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.sent.clear()
        listOf("1", "2", "3", "4").forEach { player.interceptor!!.onAdd(listOf(playNext(it))); advanceTimeBy(10); runCurrent() }
        gathered(); gathered() // the batch closes after a whole quiet window
        assertThat(sentNoPings()).containsExactly(
            ClientMessage.Suggest(track), ClientMessage.Suggest(next), ClientMessage.Suggest(third),
        ).inOrder()
        assertThat(sentNoPings()).doesNotContain(ClientMessage.Suggest(fourth))
        assertThat(notices).containsExactly("Suggested to Host")
    }

    @Test fun `the host's Play next is the room's Play next, not the end of the queue`() = runTest {
        host(v = 2, qv = 0)
        connection.sent.clear()
        player.interceptor!!.onAdd(listOf(playNext("3"))); gathered()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Add(listOf(third), next = true))
    }

    @Test fun `the host's whole queues carry the room version they were built from`() = runTest {
        host(v = 2, qv = 4)
        assertThat(loads().single().qv).isEqualTo(4) // built before the room existed: the welcome's version
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        receive(ServerMessage.QueueUpdate(listOf(next.copy(addedBy = "h")), qv = 5))
        connection.sent.clear()
        controller.send(Command.SetQueue(emptyList())); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Queue(emptyList(), qv = 5))
        // A guest's add arrives; the next song comes from it, built from version 7.
        receive(ServerMessage.QueueUpdate(listOf(third.copy(addedBy = "a")), qv = 7))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        assertThat(loads()).containsExactly(ClientMessage.Load(third.copy(addedBy = "a"), 0, emptyList(), "end", qv = 7))
    }

    @Test fun `a Load dropped while the socket was down keeps the version it was built from`() = runTest {
        host(v = 2, qv = 4)
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.up = false
        player.events!!.onEnded(); runCurrent()
        connection.up = true
        connection.sent.clear()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        // The room moved on meanwhile (a guest's add): the Load must still say 4, so the room puts that add back.
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", key = 1, v = 2, qv = 9, hostV = 2)))
        assertThat(loads()).containsExactly(ClientMessage.Load(next, 0, emptyList(), "end", qv = 4))
    }

    @Test fun `someone else's add is a notice with the song or the count and your own isn't`() = runTest {
        joinV2()
        val events = events()
        receive(ServerMessage.Added(by = "h", next = true, count = 1, track = third))
        receive(ServerMessage.Added(by = "me", next = true, count = 2, track = next))
        receive(ServerMessage.Added(by = "h", next = false, count = 3, track = next))
        assertThat(events).containsExactly(
            SessionEvent(SessionEvent.Kind.ADDED, "h", "Host", song = "Rhubarb", count = 1),
            SessionEvent(SessionEvent.Kind.ADDED, "h", "Host", song = "Xtal", count = 3),
        ).inOrder()
    }

    @Test fun `the host's switch goes to the room and the UI, and the room's answer reaches every phone`() = runTest {
        host(v = 2, qv = 0)
        connection.sent.clear()
        controller.send(Command.SetGuestsAdd(false)); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Settings(false))
        val room = controller.state.value as ListenTogetherState.InRoom
        assertThat(room.myGuestsAdd).isFalse()
        assertThat(room.roomKnowsAdds).isTrue()
        assertThat(connection.hello!!().guestsAdd).isFalse() // and a reconnect's hello says it again
        receive(ServerMessage.SettingsUpdate(guestsAdd = true))
        assertThat((controller.state.value as ListenTogetherState.InRoom).guestsAdd).isTrue()
    }

    @Test fun `an older Worker gets no settings and offers no switch`() = runTest {
        host()
        connection.sent.clear()
        controller.send(Command.SetGuestsAdd(false)); runCurrent()
        assertThat(connection.sent).isEmpty()
        assertThat((controller.state.value as ListenTogetherState.InRoom).roomKnowsAdds).isFalse()
    }

    @Test fun `a guest removes their own song at once and someone else's stays`() = runTest {
        val mine = next.copy(addedBy = "me")
        val theirs = third.copy(addedBy = "h")
        joinV2(queue = listOf(theirs, mine))
        controller.send(Command.Unqueue(theirs, 0)); runCurrent()
        assertThat(connection.sent).isEmpty()
        controller.send(Command.Unqueue(mine, 1)); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Unqueue(mine, 1))
        assertThat((controller.state.value as ListenTogetherState.InRoom).queue).containsExactly(theirs)
    }

    @Test fun `a skip because the host can't play the song names the song and the host`() = runTest {
        joinV2()
        val events = events()
        receive(ServerMessage.Prepare(2, next, 0, 8_000, by = "h", why = "unavailable"))
        assertThat(events).containsExactly(SessionEvent(SessionEvent.Kind.SKIPPED_UNAVAILABLE, "h", "Host", song = "Avril 14th"))
    }

    @Test fun `the host's own phone hears its skip of a song it can't play`() = runTest {
        host(v = 2, qv = 0)
        val events = events()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        receive(ServerMessage.Prepare(2, next, 0, 8_000, by = "h", why = "unavailable"))
        assertThat(events.single().kind).isEqualTo(SessionEvent.Kind.SKIPPED_UNAVAILABLE)
        assertThat(events.single().memberId).isEqualTo("h") // the chip says "you can't play it" on this phone
    }

    @Test fun `an outdated host's room says so to its guests`() = runTest {
        joinV2(guestsAdd = false, hostV = null)
        val room = controller.state.value as ListenTogetherState.InRoom
        assertThat(room.roomKnowsAdds).isTrue()
        assertThat(room.guestsAdd).isFalse()
        assertThat(room.hostAppOutdated).isTrue()
    }

    @Test fun `a guest tapping a song where guests can add is pointed to Play next`() = runTest {
        val notices = notices()
        joinV2()
        player.interceptor!!.onSet(listOf(item("2")), 0); runCurrent()
        assertThat(notices).containsExactly("Only the host can change the song. Use Play next to add it.")
        assertThat(connection.sent).isEmpty()
    }

    @Test fun `the host's Play with no song yet starts what guests queued`() = runTest {
        coEvery { api.create("Rawn") } returns ShareResult.Ok(RoomApiClient.Created("K7QA2PXM", "KEY", "https://x/l/K7QA2PXM"))
        session()
        controller.send(Command.Host); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = null, key = 0, v = 2, qv = 0, hostV = 2)))
        receive(ServerMessage.QueueUpdate(listOf(next.copy(addedBy = "a")), qv = 1))
        connection.sent.clear()
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(loads()).containsExactly(ClientMessage.Load(next.copy(addedBy = "a"), 0, emptyList(), "end", qv = 1))
    }

    @Test fun `the host's Play while the first song is on its way to the room doesn't skip it`() = runTest {
        host() // the first Load is out, its Prepare isn't back: the room still shows no song
        connection.sent.clear()
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Play)
    }
}
