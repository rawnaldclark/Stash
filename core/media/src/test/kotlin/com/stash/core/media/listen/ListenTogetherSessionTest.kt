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
        /** What every play/pause control would read as playWhenReady; null until the session first says. */
        var intent: Boolean? = null
        override fun setPlayIntent(playing: Boolean) { intent = playing }
        override fun userQueue() = queue
        override suspend fun saveUserPosition() { calls += "save" }
        override fun enterSession(isHost: Boolean, interceptor: SessionInterceptor) {
            calls += if (isHost) "enter:host" else "enter:listener"
            this.interceptor = interceptor
        }
        override fun exitSession() { calls += "exit" }
        override fun load(item: MediaItem, positionMs: Long) { calls += "load:${item.mediaId}@$positionMs"; ended = false }
        /** Takes the real player out of ENDED, even at the very end: paused, it settles on READY and stays there. */
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; this.positionMs = positionMs; ended = false }
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
        /** When set, asked before each send: false fails it as a dead socket does. */
        var onSend: ((ClientMessage) -> Boolean)? = null
        var hello: (() -> ClientMessage.Hello)? = null
        var scope: CoroutineScope? = null
        /** RoomClient aborts its socket when its scope is cancelled before close(), losing a frame sent just before. */
        var closedAfterScopeCancelled = false
        override val events: Flow<RoomEvent> = incoming.receiveAsFlow()
        override fun send(message: ClientMessage): Boolean {
            val goes = up && onSend?.invoke(message) != false
            if (goes) sent += message
            return goes
        }
        override fun close() { closed = true; if (scope?.isActive == false) closedAfterScopeCancelled = true }
    }

    inner class FakeCatalog : SessionCatalog {
        val items = mutableMapOf(track to item("1"), next to item("2"), third to item("3"))
        var radio: List<SharedTrack> = emptyList()
        var radioCalls = 0
        /** When set, lookups and radio wait on it: a slow DB or network. */
        var itemGate: CompletableDeferred<Unit>? = null
        var radioGate: CompletableDeferred<Unit>? = null
        /** How many songs have been looked up: a whole library selected must not mean a whole library of lookups. */
        var lookups = 0
        override suspend fun mediaItemFor(track: SharedTrack): MediaItem? { itemGate?.await(); return items[track] }
        override suspend fun sharedTrackFor(item: MediaItem): SharedTrack? {
            lookups++
            return items.entries.firstOrNull { it.value.mediaId == item.mediaId }?.key
        }
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

    fun TestScope.notices(): MutableList<String> {
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
        answered(1); answered(1, next = false)
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
        queueUpdate(many.take(20).map { it.copy(addedBy = "me") })
        answered(20)
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
        queueUpdate(List(20) { SharedTrack("M$it", "A", addedBy = "me") })
        answered(1)
        assertThat(notices).containsExactly("You can have 20 songs waiting. Add more as they play.")
    }

    @Test fun `with adds off a guest's songs still leave as one add, which the room turns into suggestions`() = runTest {
        val notices = notices()
        joinV2(guestsAdd = false)
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        assertThat(adds()).containsExactly(ClientMessage.Add(listOf(next), next = true))
        answered(0, suggested = 1)
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

    @Test fun `the host's Play with no song and nothing on its way starts what's queued`() = runTest {
        coEvery { api.create("Rawn") } returns ShareResult.Ok(RoomApiClient.Created("K7QA2PXM", "KEY", "https://x/l/K7QA2PXM"))
        session()
        controller.send(Command.Host); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        // The room's welcome already has what guests queued while this phone was connecting: the welcome itself starts it.
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = null, key = 0, queue = listOf(next.copy(addedBy = "a")), v = 2, qv = 1, hostV = 2)))
        assertThat(loads()).containsExactly(ClientMessage.Load(next.copy(addedBy = "a"), 0, emptyList(), "end", qv = 1))
        // Play as it goes out is the song on its way, not a second skip.
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(loads()).hasSize(1)
    }

    @Test fun `the host's Play while the first song is on its way to the room doesn't skip it`() = runTest {
        host() // the first Load is out, its Prepare isn't back: the room still shows no song
        connection.sent.clear()
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Play)
    }

    // ── Review fixes: a room that stops, adds the socket can't take, what the toast may claim ────────────────────────

    /** A song the catalog doesn't know: this phone can't play it. */
    val gone = SharedTrack("Gone", "Nobody")

    /** Hosting a room that knows guest adds, with [track] prepared and ready and [upcoming] after it. */
    fun TestScope.hostPlaying(upcoming: List<MediaItem> = emptyList(), qv: Int = 1) {
        host(upcoming = upcoming, v = 2, qv = qv)
        syncClock()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000, by = "h"))
        player.events!!.onReady()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 42_000, atRoomMs = 11_000, playing = true))
        connection.sent.clear()
    }

    fun TestScope.queueUpdate(queue: List<SharedTrack>, qv: Int = 8) = receive(ServerMessage.QueueUpdate(queue, qv))
    fun TestScope.timeline(key: Int, playing: Boolean, rev: Int = 5, positionMs: Long = 0) =
        receive(ServerMessage.TimelineUpdate(rev = rev, trackKey = key, positionMs = positionMs, atRoomMs = 11_000, playing = playing))
    fun TestScope.answered(count: Int, next: Boolean = true, by: String = "me", suggested: Int? = null) =
        receive(ServerMessage.Added(by, next, count, suggested = suggested))
    fun inRoom() = controller.state.value as ListenTogetherState.InRoom

    // Finding 1: the last song the host can't play.

    @Test fun `the last song the host can't play says so and pauses the room, which ignored the first pause while preparing`() = runTest {
        hostPlaying()
        val events = events()
        receive(ServerMessage.Prepare(2, gone, 0, 8_000, by = "h", why = "end"))
        assertThat(events.map { it.kind to it.song }).containsExactly(SessionEvent.Kind.SKIPPED_UNAVAILABLE to "Gone")
        assertThat(events.single().memberId).isEqualTo("h")
        assertThat(loads()).isEmpty()
        connection.sent.clear()
        // The guests are ready, so the room starts the song: it must not play on for them alone.
        timeline(2, playing = true)
        assertThat(sentNoPings()).containsExactly(ClientMessage.Pause)
        // The room's paused answer ends it, and the next song isn't paused for it.
        timeline(2, playing = false, rev = 6)
        assertThat(inRoom().pausedLocally).isFalse()
    }

    @Test fun `the last song failing while the room is already paused leaves no pause waiting for the next song`() = runTest {
        hostPlaying()
        timeline(1, playing = false, rev = 3)
        connection.sent.clear()
        player.events!!.onError()
        assertThat(sentNoPings()).doesNotContain(ClientMessage.Pause)
        assertThat(inRoom().pausedLocally).isFalse()
        val add = next.copy(addedBy = "a").also { catalog.items[it] = item("2") }
        queueUpdate(listOf(add))
        receive(ServerMessage.Prepare(2, add, 0, 8_000, by = "h"))
        player.events!!.onReady()
        connection.sent.clear()
        timeline(2, playing = true, rev = 7)
        assertThat(sentNoPings()).doesNotContain(ClientMessage.Pause)
    }

    @Test fun `the host's Play on a song it can't play asks nothing of the room when there's nowhere to go`() = runTest {
        hostPlaying()
        receive(ServerMessage.Prepare(2, gone, 0, 8_000, by = "h", why = "end"))
        timeline(2, playing = false, rev = 6)
        connection.sent.clear()
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(sentNoPings()).doesNotContain(ClientMessage.Play) // it would start the song for the guests alone
        assertThat(loads()).isEmpty()
    }

    @Test fun `a new host who can't play the room's song moves it on, saying why`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(track = gone, key = 1, queue = listOf(next), v = 2, qv = 3, hostV = 2)))
        assertThat(inRoom().unavailable).isTrue()
        connection.sent.clear()
        receive(ServerMessage.StateSync(state(host = "me", track = gone, key = 1, queue = listOf(next), v = 2, qv = 3, hostV = 2)))
        assertThat(loads()).containsExactly(ClientMessage.Load(next, 0, emptyList(), "unavailable", qv = 3))
    }

    @Test fun `a new host who can't play the last song stops the room rather than leave it stuck`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(track = gone, key = 1, timeline = RoomTimeline(0, 0, true), v = 2, qv = 3, hostV = 2)))
        connection.sent.clear()
        receive(ServerMessage.StateSync(state(host = "me", track = gone, key = 1, timeline = RoomTimeline(0, 0, true), v = 2, qv = 3, hostV = 2)))
        assertThat(sentNoPings()).contains(ClientMessage.Pause)
    }

    // Finding 2: an add to a room that has stopped.

    @Test fun `a guest's add to a room whose queue ran out starts it, without the host`() = runTest {
        hostPlaying()
        player.ended = true
        player.events!!.onEnded(); runCurrent()
        connection.sent.clear()
        queueUpdate(listOf(next.copy(addedBy = "a")))
        assertThat(loads()).containsExactly(ClientMessage.Load(next.copy(addedBy = "a"), 0, emptyList(), "end", qv = 8))
    }

    @Test fun `a guest's add to a room stopped on a song the host can't play starts it`() = runTest {
        hostPlaying()
        receive(ServerMessage.Prepare(2, gone, 0, 8_000, by = "h", why = "end"))
        timeline(2, playing = true)
        connection.sent.clear()
        queueUpdate(listOf(next.copy(addedBy = "a")))
        assertThat(loads()).containsExactly(ClientMessage.Load(next.copy(addedBy = "a"), 0, emptyList(), "unavailable", qv = 8))
        assertThat(inRoom().pausedLocally).isFalse()
    }

    @Test fun `a guest's add to a room that never had a song starts it, and the echo doesn't start it twice`() = runTest {
        coEvery { api.create("Rawn") } returns ShareResult.Ok(RoomApiClient.Created("K7QA2PXM", "KEY", "https://x/l/K7QA2PXM"))
        session()
        controller.send(Command.Host); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = null, key = 0, v = 2, qv = 0, hostV = 2)))
        val songs = listOf(next.copy(addedBy = "a"), third.copy(addedBy = "a")).onEach { catalog.items[it] = item("2") }
        queueUpdate(songs, qv = 1)
        assertThat(loads()).containsExactly(ClientMessage.Load(songs[0], 0, songs.drop(1), "end", qv = 1))
        // Another add crosses it, then the room's echo of the Load: still the one Load.
        queueUpdate(songs + track.copy(addedBy = "b"), qv = 2)
        assertThat(loads()).hasSize(1)
        receive(ServerMessage.Prepare(1, songs[0], 0, 8_000, by = "h", why = "end"))
        queueUpdate(listOf(songs[1], track.copy(addedBy = "b")), qv = 3)
        assertThat(loads()).hasSize(1)
    }

    @Test fun `an add while the host's song is playing or paused only joins the queue`() = runTest {
        hostPlaying()
        queueUpdate(listOf(next.copy(addedBy = "a")))
        assertThat(loads()).isEmpty()
        timeline(1, playing = false)
        queueUpdate(listOf(next.copy(addedBy = "a"), third.copy(addedBy = "a")), qv = 9)
        assertThat(loads()).isEmpty()
    }

    @Test fun `a listener's phone never moves a stopped room on`() = runTest {
        joinV2()
        player.ended = true
        queueUpdate(listOf(next.copy(addedBy = "a")))
        assertThat(loads()).isEmpty()
    }

    @Test fun `a host back from a drop finds an add made while it was gone and starts it`() = runTest {
        hostPlaying()
        player.ended = true
        player.events!!.onEnded(); runCurrent()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        connection.sent.clear()
        val pausedAtTheEnd = RoomTimeline(125_020, 11_000, false)
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", key = 1, timeline = pausedAtTheEnd, queue = listOf(next.copy(addedBy = "a")), v = 2, qv = 9, hostV = 2)))
        assertThat(loads()).containsExactly(ClientMessage.Load(next.copy(addedBy = "a"), 0, emptyList(), "end", qv = 9))
    }

    @Test fun `a song ending as an add arrives moves on once, not twice`() = runTest {
        hostPlaying(upcoming = listOf(item("2"), item("3")))
        player.ended = true
        player.events!!.onEnded() // no runCurrent: the Load must already be out when the next message is read
        queueUpdate(listOf(next, third, track.copy(addedBy = "a")))
        runCurrent()
        assertThat(loads()).hasSize(1)
        assertThat(loads().single().track).isEqualTo(next)
    }

    // N1: the queue ran out a while ago. The room's paused answer made the player seek, so it no longer reads ENDED.

    fun TestScope.queueRanOut(pausedAt: Long = 125_020) {
        hostPlaying()
        player.ended = true
        player.events!!.onEnded(); runCurrent()
        assertThat(sentNoPings()).contains(ClientMessage.Pause)
        timeline(1, playing = false, rev = 3, positionMs = pausedAt)
        assertThat(player.calls).contains("seek:$pausedAt")
        assertThat(player.ended).isFalse()
        connection.sent.clear()
    }

    @Test fun `a guest's add well after the queue ran out starts the room`() = runTest {
        queueRanOut()
        val add = next.copy(addedBy = "a")
        queueUpdate(listOf(add))
        assertThat(loads()).containsExactly(ClientMessage.Load(add, 0, emptyList(), "end", qv = 8))
    }

    @Test fun `the host's own add well after the queue ran out starts it too`() = runTest {
        queueRanOut()
        val add = next.copy(addedBy = "h")
        queueUpdate(listOf(add))
        assertThat(loads()).containsExactly(ClientMessage.Load(add, 0, emptyList(), "end", qv = 8))
    }

    @Test fun `the room's position landing just short of the end still counts as finished`() = runTest {
        queueRanOut(pausedAt = 124_100)
        queueUpdate(listOf(next.copy(addedBy = "a")))
        assertThat(loads()).hasSize(1)
    }

    @Test fun `a pause by hand over a second before the end stays paused when a song is added`() = runTest {
        hostPlaying()
        timeline(1, playing = false, rev = 3, positionMs = 123_900)
        connection.sent.clear()
        queueUpdate(listOf(next.copy(addedBy = "a")))
        assertThat(loads()).isEmpty()
    }

    @Test fun `a new host who finds the room paused at the song's end with a song waiting moves it on`() = runTest {
        join()
        val pausedAtTheEnd = RoomTimeline(125_020, 0, false)
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = pausedAtTheEnd, queue = listOf(next), v = 2, qv = 3, hostV = 2)))
        assertThat(player.ended).isFalse()
        connection.sent.clear()
        receive(ServerMessage.StateSync(state(host = "me", key = 1, timeline = pausedAtTheEnd, queue = listOf(next), v = 2, qv = 3, hostV = 2)))
        assertThat(loads()).containsExactly(ClientMessage.Load(next, 0, emptyList(), "end", qv = 3))
    }

    // N2: a stop says why, on every phone, once.

    @Test fun `a stop tells the host with a toast, wherever in the app they are`() = runTest {
        val notices = notices()
        hostPlaying()
        receive(ServerMessage.Prepare(2, gone, 0, 8_000, by = "h", why = "end"))
        assertThat(notices).containsExactly("Session paused: you can't play Gone")
    }

    @Test fun `a stop with songs still queued says Play tries the next one`() = runTest {
        val notices = notices()
        val goners = List(3) { SharedTrack("G$it", "Nobody") }
        host(upcoming = emptyList(), v = 2, qv = 1)
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000, by = "h"))
        queueUpdate(goners, qv = 2)
        player.events!!.onError() // 1
        echoUnplayable(2, goners[0]) // 2
        queueUpdate(goners.drop(1), qv = 3)
        echoUnplayable(3, goners[1]) // 3
        assertThat(notices).containsExactly("Session paused: you can't play G1. Tap Play to try the next song.")
    }

    @Test fun `an add after a stop moves on saying why, and the host's chip for that song isn't repeated`() = runTest {
        hostPlaying()
        val events = events()
        receive(ServerMessage.Prepare(2, gone, 0, 8_000, by = "h", why = "end"))
        timeline(2, playing = true)
        val add = next.copy(addedBy = "a").also { catalog.items[it] = item("2") }
        connection.sent.clear()
        queueUpdate(listOf(add))
        assertThat(loads().single().why).isEqualTo("unavailable")
        receive(ServerMessage.Prepare(3, add, 0, 8_000, by = "h", why = "unavailable"))
        assertThat(events.map { it.song }).containsExactly("Gone")
    }

    /** A guest's view: the host has just been given [song] and says it can't play it. */
    fun TestScope.hostCantPlay(song: SharedTrack) {
        catalog.items[song] = item("2")
        receive(ServerMessage.Prepare(2, song, 0, 8_000, by = "h", why = "end"))
        receive(ServerMessage.Members(listOf(RoomMember("h", "Host", 1, "unavailable", v = 2), RoomMember("me", "Me", 2, "ok", v = 2))))
        timeline(2, playing = true, rev = 6)
    }

    @Test fun `guests hear why when the host pauses on a song it can't play, and the adder gets the toast, once`() = runTest {
        val notices = notices()
        joinV2()
        val events = events()
        hostCantPlay(next.copy(addedBy = "me"))
        receive(ServerMessage.TimelineUpdate(rev = 7, trackKey = 2, positionMs = 0, atRoomMs = 11_000, playing = false, by = "h"))
        assertThat(events).containsExactly(SessionEvent(SessionEvent.Kind.SKIPPED_UNAVAILABLE, "h", "Host", song = "Xtal"))
        assertThat(notices).containsExactly("Skipped Xtal: Host can't play it")
        // The room moves on later, for the next add: everyone has already been told.
        receive(ServerMessage.Prepare(3, third, 0, 8_000, by = "h", why = "unavailable"))
        assertThat(events).hasSize(1)
        assertThat(notices).hasSize(1)
    }

    @Test fun `a guest who didn't add it gets the notice and no toast`() = runTest {
        val notices = notices()
        joinV2()
        val events = events()
        hostCantPlay(next.copy(addedBy = "b"))
        receive(ServerMessage.TimelineUpdate(rev = 7, trackKey = 2, positionMs = 0, atRoomMs = 11_000, playing = false, by = "h"))
        assertThat(events.single().kind).isEqualTo(SessionEvent.Kind.SKIPPED_UNAVAILABLE)
        assertThat(notices).isEmpty()
    }

    @Test fun `a host's ordinary pause still says paused`() = runTest {
        joinV2()
        val events = events()
        timeline(1, playing = true, rev = 6)
        receive(ServerMessage.TimelineUpdate(rev = 7, trackKey = 1, positionMs = 0, atRoomMs = 11_000, playing = false, by = "h"))
        assertThat(events.single().kind).isEqualTo(SessionEvent.Kind.PAUSED)
    }

    // Finding 3: an add while the socket is down.

    @Test fun `an add while the socket is down is held and sent after the next welcome, with no false success`() = runTest {
        val notices = notices()
        joinV2()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.up = false
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        player.interceptor!!.onAdd(listOf(playNext("3"))); gathered()
        player.interceptor!!.onAdd(listOf(item("1"))); gathered()
        assertThat(adds()).isEmpty()
        assertThat(notices).containsExactly(
            "Not connected right now. It'll go in when you're back.",
            "Not connected right now. It'll go in when you're back.",
            "Not connected right now. It'll go in when you're back.",
        )
        notices.clear()
        connection.up = true
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        assertThat(adds()).isEmpty() // the room hasn't welcomed this socket yet
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, v = 2, guestsAdd = true, qv = 5, hostV = 2)))
        assertThat(adds()).containsExactly(
            ClientMessage.Add(listOf(next, third), next = true),
            ClientMessage.Add(listOf(track), next = false),
        ).inOrder()
        assertThat(notices).isEmpty() // not until the room says so
        answered(2); answered(1, next = false)
        assertThat(notices).containsExactly("Added to the session queue")
    }

    @Test fun `a socket that drops before the session says so still holds the add`() = runTest {
        joinV2()
        connection.up = false // no Reconnecting event yet: send() is the first to know
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        connection.up = true
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, v = 2, guestsAdd = true, qv = 5, hostV = 2)))
        assertThat(adds()).containsExactly(ClientMessage.Add(listOf(next), next = true))
    }

    @Test fun `an add before the first welcome is held too`() = runTest {
        val notices = notices()
        join()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        assertThat(adds()).isEmpty()
        assertThat(notices).containsExactly("Not connected right now. It'll go in when you're back.")
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, v = 2, guestsAdd = true, qv = 5, hostV = 2)))
        assertThat(adds()).containsExactly(ClientMessage.Add(listOf(next), next = true))
    }

    @Test fun `the socket dropping partway through a big selection holds only what didn't go`() = runTest {
        val many = songs(120)
        host(v = 2, qv = 0)
        connection.sent.clear()
        var addsLeft = 1
        connection.onSend = { it !is ClientMessage.Add || addsLeft-- > 0 }
        player.interceptor!!.onAdd(many.indices.map { playNext("s$it") }); gathered()
        assertThat(adds().map { it.tracks.size }).containsExactly(50)
        connection.onSend = null
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        connection.sent.clear()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = track, key = 1, v = 2, qv = 2, hostV = 2)))
        assertThat(adds().flatMap { it.tracks }).containsExactlyElementsIn(many.drop(50)).inOrder()
        assertThat(adds().map { it.tracks.size }).containsExactly(50, 20).inOrder()
    }

    @Test fun `held adds are checked against the room as it is after the drop`() = runTest {
        val notices = notices()
        joinV2()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        notices.clear()
        // 20 of this guest's songs got in another way, before the socket went: nothing to send, and it says why.
        val mine = List(20) { SharedTrack("M$it", "A", addedBy = "me") }
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, queue = mine, v = 2, guestsAdd = true, qv = 5, hostV = 2)))
        assertThat(adds()).isEmpty()
        assertThat(notices).containsExactly("You can have 20 songs waiting. Add more as they play.")
    }

    @Test fun `leaving forgets what was held`() = runTest {
        joinV2()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        controller.send(Command.Leave); runCurrent()
        controller.send(Command.Join("ZZ22ZZ22")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        connection.sent.clear()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, v = 2, guestsAdd = true, qv = 5, hostV = 2)))
        assertThat(adds()).isEmpty()
    }

    // Finding 5: an add during the radio lookup.

    @Test fun `an add during the radio lookup plays, and the station doesn't wipe it`() = runTest {
        autoplay = true
        catalog.radio = listOf(third, track)
        catalog.radioGate = CompletableDeferred()
        hostPlaying()
        player.ended = true
        player.events!!.onEnded(); runCurrent() // waiting on the station
        assertThat(catalog.radioCalls).isEqualTo(1)
        val add = next.copy(addedBy = "a")
        queueUpdate(listOf(add))
        assertThat(loads()).containsExactly(ClientMessage.Load(add, 0, emptyList(), "end", qv = 8))
        catalog.radioGate!!.complete(Unit); runCurrent()
        assertThat(loads()).hasSize(1)
    }

    @Test fun `an add that reaches the queue just as the lookup ends is played instead of the station`() = runTest {
        autoplay = true
        catalog.radio = listOf(third)
        catalog.radioGate = CompletableDeferred()
        hostPlaying()
        // Not ended, so the add alone doesn't start it: the host skipped, and is waiting on the station.
        player.interceptor!!.onNext(); runCurrent()
        val add = next.copy(addedBy = "a")
        queueUpdate(listOf(add))
        assertThat(loads()).isEmpty()
        catalog.radioGate!!.complete(Unit); runCurrent()
        assertThat(loads()).containsExactly(ClientMessage.Load(add, 0, emptyList(), "skip", qv = 8))
    }

    @Test fun `a song the host picked during the lookup isn't replaced by the station`() = runTest {
        autoplay = true
        catalog.radio = listOf(third)
        catalog.radioGate = CompletableDeferred()
        hostPlaying()
        player.ended = true
        player.events!!.onEnded(); runCurrent()
        player.interceptor!!.onSet(listOf(item("2")), 0); runCurrent()
        receive(ServerMessage.Prepare(2, next, 0, 8_000, by = "h", why = "pick"))
        catalog.radioGate!!.complete(Unit); runCurrent()
        assertThat(loads().map { it.track }).containsExactly(next)
    }

    @Test fun `no add during the lookup and the station plays as before`() = runTest {
        autoplay = true
        catalog.radio = listOf(third, next)
        catalog.radioGate = CompletableDeferred()
        hostPlaying()
        player.ended = true
        player.events!!.onEnded(); runCurrent()
        catalog.radioGate!!.complete(Unit); runCurrent()
        assertThat(loads()).containsExactly(ClientMessage.Load(third, 0, listOf(next), "radio", qv = 1))
    }

    // Finding 6: a host that can't play anything.

    /** The room's echo of the song this phone just sent, which it can't play: the host's next move is the thing tested. */
    fun TestScope.echoUnplayable(key: Int, song: SharedTrack) = receive(ServerMessage.Prepare(key, song, 0, 8_000, by = "h", why = "unavailable"))

    @Test fun `a run of songs the host can't play stops after three, and the rest of the queue stays`() = runTest {
        val goners = List(5) { SharedTrack("G$it", "Nobody") }
        host(upcoming = emptyList(), v = 2, qv = 1)
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000, by = "h"))
        queueUpdate(goners, qv = 2)
        val events = events()
        connection.sent.clear()
        player.events!!.onError() // 1: moves to G0
        assertThat(loads().map { it.track }).containsExactly(goners[0])
        echoUnplayable(2, goners[0]) // 2: moves to G1, before this phone has read the room's echo of G0's Load
        assertThat(loads().map { it.track }).containsExactly(goners[0], goners[1]).inOrder()
        queueUpdate(goners.drop(1), qv = 3) // that echo
        echoUnplayable(3, goners[1]) // 3: stops here
        assertThat(loads()).hasSize(2)
        assertThat(sentNoPings()).contains(ClientMessage.Pause)
        queueUpdate(goners.drop(2), qv = 4) // the echo of G1's Load
        assertThat(inRoom().queue).containsExactlyElementsIn(goners.drop(2)).inOrder()
        assertThat(events.last().song).isEqualTo("G1")
        // An add doesn't burn another song: the host stopped this on purpose.
        queueUpdate(goners.drop(2) + next.copy(addedBy = "a"), qv = 5)
        assertThat(loads()).hasSize(2)
        // The host's Play starts it again, with a fresh count, and every phone hears about the song it left.
        player.interceptor!!.onPlay(); runCurrent()
        assertThat(loads()).hasSize(3)
        assertThat(loads().last().track).isEqualTo(goners[2])
        assertThat(loads().last().why).isEqualTo("unavailable")
        echoUnplayable(4, goners[2])
        assertThat(loads()).hasSize(4)
    }

    @Test fun `a song the host can play resets the count`() = runTest {
        host(upcoming = emptyList(), v = 2, qv = 1)
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000, by = "h"))
        val goners = List(4) { SharedTrack("G$it", "Nobody") }
        queueUpdate(listOf(goners[0], next, goners[1], goners[2], goners[3]), qv = 2)
        connection.sent.clear()
        player.events!!.onError() // 1
        echoUnplayable(2, goners[0]) // 2
        queueUpdate(listOf(next, goners[1], goners[2], goners[3]), qv = 3) // the room's echo of G0's Load
        receive(ServerMessage.Prepare(3, next, 0, 8_000, by = "h", why = "unavailable"))
        queueUpdate(listOf(goners[1], goners[2], goners[3]), qv = 4) // of next's
        player.events!!.onReady() // played: back to none
        player.events!!.onError() // 1
        echoUnplayable(4, goners[1]) // 2, so this one still moves on; without the reset it would be 4 and stop
        assertThat(loads().map { it.track }).containsExactly(goners[0], next, goners[1], goners[2]).inOrder()
        queueUpdate(listOf(goners[2], goners[3]), qv = 5) // of G1's
        echoUnplayable(5, goners[2]) // 3: now it stops
        assertThat(loads()).hasSize(4)
    }

    @Test fun `an Offline-style host with radio on doesn't loop through stations`() = runTest {
        autoplay = true
        catalog.radio = List(5) { SharedTrack("R$it", "Nobody") }
        hostPlaying()
        player.events!!.onError(); runCurrent() // 1: the first station
        assertThat(catalog.radioCalls).isEqualTo(1)
        var key = 2
        repeat(6) {
            val sent = loads().lastOrNull() ?: return@repeat
            receive(ServerMessage.Prepare(key++, sent.track, 0, 8_000, by = "h", why = "unavailable")); runCurrent()
        }
        assertThat(loads().size).isAtMost(2)
        assertThat(catalog.radioCalls).isAtMost(1)
        assertThat(sentNoPings()).contains(ClientMessage.Pause)
    }

    // Finding 7: selecting more than the room can hold.

    @Test fun `a selection of 1,001 songs stays under the room's message limit and looks up only what could go in`() = runTest {
        songs(1_001)
        host(v = 2, qv = 0)
        connection.sent.clear()
        catalog.lookups = 0
        player.interceptor!!.onAdd(List(1_001) { playNext("s$it") }); gathered()
        assertThat(adds().size).isAtMost(4)
        assertThat(adds().flatMap { it.tracks }).hasSize(200)
        assertThat(catalog.lookups).isEqualTo(200)
    }

    @Test fun `a guest with adds off selecting many sends 3, as many as can wait`() = runTest {
        songs(30)
        joinV2(guestsAdd = false)
        player.interceptor!!.onAdd(List(30) { playNext("s$it") }); gathered()
        assertThat(adds()).hasSize(1)
        assertThat(adds().single().tracks).hasSize(3)
    }

    @Test fun `a guest selecting 1,001 songs tap by tap stays one message per batch`() = runTest {
        songs(1_001)
        joinV2()
        for (i in 0 until 1_001) player.interceptor!!.onAdd(listOf(playNext("s$i")))
        gathered(); gathered()
        assertThat(adds().flatMap { it.tracks }).hasSize(20)
        assertThat(adds()).hasSize(1)
    }

    // Finding 9: a host's edit built from what was on screen.

    @Test fun `the host's edit carries the version of the queue on screen, not the newest`() = runTest {
        host(v = 2, qv = 4)
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000, by = "h"))
        queueUpdate(listOf(next), qv = 5)
        assertThat(inRoom().qv).isEqualTo(5)
        // A guest's add arrives after the screen composed version 5, and before the swipe lands.
        queueUpdate(listOf(third.copy(addedBy = "a"), next), qv = 6)
        connection.sent.clear()
        controller.send(Command.SetQueue(emptyList(), qv = 5)); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Queue(emptyList(), qv = 5))
    }

    // Finding 10: a message right after a pick.

    @Test fun `a skip right after a new album keeps other people's adds, which the room had put in front of it`() = runTest {
        hostPlaying()
        val theirs = next.copy(addedBy = "a")
        queueUpdate(listOf(theirs, track.copy(addedBy = "h")), qv = 5)
        connection.sent.clear()
        player.interceptor!!.onSet(listOf(item("3"), item("1")), 0); runCurrent()
        assertThat(loads().single()).isEqualTo(ClientMessage.Load(third, 0, listOf(track), "pick", qv = 5))
        player.interceptor!!.onNext(); runCurrent()
        assertThat(loads().last()).isEqualTo(ClientMessage.Load(theirs, 0, listOf(track), "skip", qv = 5))
    }

    // Finding 13: telling the song's adder.

    @Test fun `the guest who added the song the host can't play gets a toast wherever they are`() = runTest {
        val notices = notices()
        join()
        receive(ServerMessage.Welcome("me", "tok", state(track = next.copy(addedBy = "me"), key = 1, v = 2, guestsAdd = true, qv = 3, hostV = 2)))
        receive(ServerMessage.Prepare(2, third, 0, 8_000, by = "h", why = "unavailable"))
        assertThat(notices).containsExactly("Skipped Xtal: Host can't play it")
    }

    @Test fun `nobody else gets that toast, and the host has the chip`() = runTest {
        val notices = notices()
        join()
        receive(ServerMessage.Welcome("me", "tok", state(track = next.copy(addedBy = "b"), key = 1, v = 2, guestsAdd = true, qv = 3, hostV = 2)))
        receive(ServerMessage.Prepare(2, third, 0, 8_000, by = "h", why = "unavailable"))
        assertThat(notices).isEmpty()
    }

    // Finding 14: the toast comes from the room's answer.

    @Test fun `the toast waits for the room's answer and for every message of the batch`() = runTest {
        val many = songs(60)
        val notices = notices()
        host(v = 2, qv = 0)
        connection.sent.clear()
        player.interceptor!!.onAdd(many.indices.map { playNext("s$it") }); gathered()
        assertThat(adds()).hasSize(2)
        assertThat(notices).isEmpty()
        answered(50, by = "h")
        assertThat(notices).isEmpty()
        answered(10, by = "h")
        assertThat(notices).containsExactly("Playing next in the session")
    }

    @Test fun `a refusal says the queue is full, not that the song went in`() = runTest {
        val notices = notices()
        joinV2()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        answered(0)
        assertThat(notices).containsExactly("The session queue is full")
    }

    @Test fun `a refusal because this guest's count was stale says it's their 20`() = runTest {
        val notices = notices()
        joinV2()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        queueUpdate(List(20) { SharedTrack("M$it", "A", addedBy = "me") }) // they got in from this phone's last batch
        answered(0)
        assertThat(notices).containsExactly("You can have 20 songs waiting. Add more as they play.")
    }

    @Test fun `only some going in says why the rest didn't`() = runTest {
        val notices = notices()
        joinV2(queue = List(18) { SharedTrack("M$it", "A", addedBy = "me") })
        player.interceptor!!.onAdd(listOf(playNext("1"), playNext("2"), playNext("3"))); gathered()
        assertThat(adds().single().tracks).hasSize(2)
        queueUpdate(List(20) { SharedTrack("M$it", "A", addedBy = "me") })
        answered(2)
        assertThat(notices).containsExactly("You can have 20 songs waiting. Add more as they play.")
    }

    @Test fun `the switch turning off as the add crosses it says suggested, and on says added`() = runTest {
        val notices = notices()
        joinV2()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        answered(0, suggested = 1)
        assertThat(notices).containsExactly("Suggested to Host")
        notices.clear()
        receive(ServerMessage.SettingsUpdate(guestsAdd = false))
        player.interceptor!!.onAdd(listOf(playNext("3"))); gathered()
        answered(1) // the host turned it back on before this reached the room
        assertThat(notices).containsExactly("Playing next in the session")
    }

    @Test fun `a full tray says the host still has their suggestions`() = runTest {
        val notices = notices()
        joinV2(guestsAdd = false)
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        answered(0, suggested = 0)
        assertThat(notices).containsExactly("The host still has your suggestions. Try again once they've answered.")
    }

    @Test fun `a drop forgets answers that won't come, so the next one isn't read as theirs`() = runTest {
        val notices = notices()
        joinV2()
        player.interceptor!!.onAdd(listOf(playNext("2"))); gathered()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, v = 2, guestsAdd = true, qv = 5, hostV = 2)))
        answered(0)
        assertThat(notices).isEmpty()
    }

    @Test fun `someone's empty answer is no chip`() = runTest {
        joinV2()
        val events = events()
        receive(ServerMessage.Added(by = "h", next = true, count = 0))
        assertThat(events).isEmpty()
    }

    // ── 2026-11-07 on devices: a guest's song the host's sources never find. The room started it for the guests at its
    // 8 s deadline, the host's player looked for ~41 s, the host's Pause did nothing, and the skip came only after that.

    /** Hosting a room that knows guest adds: song 1 prepared, the guest "me" ready for it, this phone ("h") still looking. */
    fun TestScope.hostWaitingOn(upcoming: List<MediaItem> = listOf(item("2"))) {
        host(upcoming = upcoming, v = 2, qv = 1)
        syncClock() // room = local + 10 000
        receive(ServerMessage.Prepare(1, track, 0, 8_000, by = "h"))
        connection.sent.clear()
    }

    /** The room's own start at its deadline, 500 ms ahead (room.js START_LEAD_MS), though this host isn't ready. */
    fun TestScope.roomStartsWithoutTheHost() = receive(
        ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 0, atRoomMs = testScheduler.currentTime + 10_500, playing = true),
    )

    @Test fun `a song the host still hasn't got 4 s after the guests started hearing it is skipped for everyone`() = runTest {
        hostWaitingOn()
        val events = events()
        roomStartsWithoutTheHost() // the guests start at room 10 500 = local 500
        advanceTimeBy(4_499); runCurrent()
        assertThat(statuses()).isEmpty()
        assertThat(loads()).isEmpty()
        advanceTimeBy(1); runCurrent() // local 4 500: 4 s of it on their own, not the ~40 s the resolver takes to give up
        assertThat(sentNoPings()).containsExactly(
            ClientMessage.Status("unavailable", 1),
            ClientMessage.Load(next, 0, emptyList(), "unavailable", qv = 1),
        ).inOrder()
        assertThat(ShadowLog.getLogsForTag("ListenTogether").map { it.msg }).contains(
            "song #1 unavailable: not ready 4000 ms after the room started it for the others",
        )
        // The player's own failure, should it still come for that song, changes nothing.
        player.events!!.onError()
        assertThat(statuses()).hasSize(1)
        assertThat(loads()).hasSize(1)
        // The room's echo: "Skipped Avril 14th: … can't play it" on every phone, as for a song the host couldn't find at all.
        receive(ServerMessage.Prepare(2, next, 0, 8_000, by = "h", why = "unavailable"))
        assertThat(events.map { it.kind to it.song }).containsExactly(SessionEvent.Kind.SKIPPED_UNAVAILABLE to "Avril 14th")
    }

    @Test fun `with nothing after it the room pauses there and the host is told, as when Offline`() = runTest {
        val notices = notices()
        hostWaitingOn(upcoming = emptyList())
        roomStartsWithoutTheHost()
        advanceTimeBy(4_500); runCurrent()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Status("unavailable", 1), ClientMessage.Pause).inOrder()
        assertThat(notices).containsExactly("Session paused: you can't play Avril 14th")
    }

    @Test fun `a slow find within the 4 s joins the room where it is, and nothing is skipped`() = runTest {
        hostWaitingOn()
        roomStartsWithoutTheHost()
        advanceTimeBy(3_000); runCurrent() // local 3 000 = room 13 000: the guests are 2.5 s in
        player.calls.clear()
        player.events!!.onReady()
        // The next whole room second at least 1 s ahead of 13 000 is 14 000, where the song is at 3 500.
        assertThat(player.calls).containsExactly("pause", "seek:3500").inOrder()
        advanceTimeBy(1_000); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
        advanceTimeBy(30_000); runCurrent()
        assertThat(statuses().map { it.status }).doesNotContain("unavailable")
        assertThat(loads()).isEmpty()
    }

    @Test fun `no clock runs while nobody else hears the song, and it starts when someone does`() = runTest {
        hostWaitingOn()
        receive(ServerMessage.Members(listOf(RoomMember("h", "Host", 1, "buffering", v = 2), RoomMember("me", "Me", 2, "buffering", v = 2))))
        roomStartsWithoutTheHost()
        advanceTimeBy(30_000); runCurrent()
        assertThat(statuses()).isEmpty() // alone with it, or everyone still loading: the host's resolver takes the time it needs
        receive(ServerMessage.Members(listOf(RoomMember("h", "Host", 1, "buffering", v = 2), RoomMember("me", "Me", 2, "ok", v = 2))))
        advanceTimeBy(3_999); runCurrent()
        assertThat(statuses()).isEmpty()
        advanceTimeBy(1); runCurrent()
        assertThat(statuses()).containsExactly(ClientMessage.Status("unavailable", 1))
    }

    @Test fun `a listener still loading a song the room plays reports nothing early, as only the host moves the room`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 5_000, true))))
        connection.sent.clear()
        advanceTimeBy(30_000); runCurrent()
        assertThat(statuses()).isEmpty()
    }

    @Test fun `while the song is still being looked for every control reads playing, so the host's tap is a Pause`() = runTest {
        hostWaitingOn()
        // Before the fix the held-paused player read "paused", the toggle sent Play, and the room dropped it.
        assertThat(player.intent).isTrue()
        player.interceptor!!.onPause()
        assertThat(player.intent).isFalse() // at once: ListenTogetherPlayer reads it as the pause returns
        assertThat(sentNoPings()).containsExactly(ClientMessage.Pause) // the room drops it while preparing…
        connection.sent.clear()
        roomStartsWithoutTheHost()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Pause) // …so its start gets it again
        receive(ServerMessage.TimelineUpdate(rev = 3, trackKey = 1, positionMs = 0, atRoomMs = 10_000, playing = false, by = "h"))
        assertThat(pausedLocally()).isFalse()
        assertThat(player.intent).isFalse()
        advanceTimeBy(30_000); runCurrent()
        assertThat(statuses()).isEmpty() // paused, nobody hears it alone: no clock
        assertThat(player.calls).doesNotContain("play")
    }

    @Test fun `the host's pause after the room started without it pauses everyone and stops the clock`() = runTest {
        hostWaitingOn()
        roomStartsWithoutTheHost()
        assertThat(player.intent).isTrue()
        advanceTimeBy(2_000); runCurrent()
        player.interceptor!!.onPause()
        assertThat(sentNoPings()).containsExactly(ClientMessage.Pause)
        receive(ServerMessage.TimelineUpdate(rev = 3, trackKey = 1, positionMs = 1_500, atRoomMs = 12_000, playing = false, by = "h"))
        advanceTimeBy(10_000); runCurrent()
        assertThat(statuses()).isEmpty()
        // Play again with the song still not found: the guests would hear it alone, and its 4 s are spent.
        player.interceptor!!.onPlay()
        receive(ServerMessage.TimelineUpdate(rev = 4, trackKey = 1, positionMs = 1_500, atRoomMs = 22_400, playing = true, by = "h"))
        runCurrent()
        assertThat(statuses()).containsExactly(ClientMessage.Status("unavailable", 1))
    }

    @Test fun `a song found unplayable while the host has the room paused moves on, and the next song stays paused`() = runTest {
        hostWaitingOn()
        roomStartsWithoutTheHost()
        player.interceptor!!.onPause()
        receive(ServerMessage.TimelineUpdate(rev = 3, trackKey = 1, positionMs = 0, atRoomMs = 10_500, playing = false, by = "h"))
        connection.sent.clear()
        player.events!!.onError() // the resolver gave up, ~40 s in
        assertThat(sentNoPings()).containsExactly(
            ClientMessage.Status("unavailable", 1),
            ClientMessage.Load(next, 0, emptyList(), "unavailable", qv = 1),
        ).inOrder()
        assertThat(player.intent).isFalse()
        receive(ServerMessage.Prepare(2, next, 0, 8_000, by = "h", why = "unavailable"))
        player.events!!.onReady()
        connection.sent.clear()
        receive(ServerMessage.TimelineUpdate(rev = 5, trackKey = 2, positionMs = 0, atRoomMs = 20_000, playing = true))
        assertThat(sentNoPings()).containsExactly(ClientMessage.Pause)
        advanceTimeBy(15_000); runCurrent()
        assertThat(player.calls).doesNotContain("play")
    }

    @Test fun `every control reads the room's intent, and a pause reads paused before the room answers`() = runTest {
        host(v = 2, qv = 1); syncClock()
        assertThat(player.intent).isFalse() // no song yet
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000, by = "h"))
        assertThat(player.intent).isTrue() // the room starts what it prepares: Pause, with a spinner while it loads
        player.events!!.onReady()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 42_000, atRoomMs = 11_000, playing = true))
        assertThat(player.intent).isTrue()
        player.interceptor!!.onPause()
        assertThat(player.intent).isFalse()
        receive(ServerMessage.TimelineUpdate(rev = 3, trackKey = 1, positionMs = 43_000, atRoomMs = 11_500, playing = false, by = "h"))
        assertThat(player.intent).isFalse()
        player.interceptor!!.onPlay()
        assertThat(player.intent).isFalse() // the room's answer starts it
        receive(ServerMessage.TimelineUpdate(rev = 4, trackKey = 1, positionMs = 43_000, atRoomMs = 12_000, playing = true, by = "h"))
        assertThat(player.intent).isTrue()
    }

    @Test fun `a listener's controls read playing while their phone still loads the room's song, and their pause holds`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(0, 5_000, true))))
        assertThat(player.intent).isTrue()
        player.interceptor!!.onPause()
        assertThat(player.intent).isFalse()
        player.events!!.onReady()
        advanceTimeBy(5_000); runCurrent()
        assertThat(player.calls).doesNotContain("play")
    }
}
