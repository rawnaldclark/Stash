package com.stash.core.media.listen

import android.util.Log
import androidx.media3.common.MediaItem
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.listen.RoomConnection
import com.stash.core.data.listen.RoomEvent
import com.stash.core.data.share.ShareResult
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomPhase
import com.stash.core.model.listen.RoomState
import com.stash.core.model.listen.RoomTimeline
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
        var hello: (() -> ClientMessage.Hello)? = null
        override val events: Flow<RoomEvent> = incoming.receiveAsFlow()
        override fun send(message: ClientMessage): Boolean { sent += message; return true }
        override fun close() { closed = true }
    }

    inner class FakeCatalog : SessionCatalog {
        val items = mutableMapOf(track to item("1"), next to item("2"), third to item("3"))
        var radio: List<SharedTrack> = emptyList()
        var radioCalls = 0
        override suspend fun mediaItemFor(track: SharedTrack) = items[track]
        override suspend fun sharedTrackFor(item: MediaItem) = items.entries.firstOrNull { it.value.mediaId == item.mediaId }?.key
        override suspend fun radioAfter(track: SharedTrack): List<SharedTrack> { radioCalls++; return radio }
    }

    val player = FakePlayer()
    val connection = FakeConnection()
    val catalog = FakeCatalog()
    val api: RoomApiClient = mockk()
    val controller = ListenTogetherController(mockk(relaxed = true)).apply { serviceAttached = true }
    var autoplay = false

    fun TestScope.session() = ListenTogetherSession(
        scope = backgroundScope,
        controller = controller,
        player = player,
        catalog = catalog,
        api = api,
        connector = { _, _, hello -> connection.hello = hello; connection },
        autoplayRadio = { autoplay },
        displayName = { "Rawn" },
        clock = { testScheduler.currentTime },
    ).also { it.start() }

    fun state(
        host: String? = "h",
        track: SharedTrack? = this.track,
        key: Int = 1,
        timeline: RoomTimeline = RoomTimeline(0, 0, false),
        queue: List<SharedTrack> = emptyList(),
    ) = RoomState(
        rev = 1, host = host, track = track, trackKey = key, timeline = timeline, queue = queue,
        members = listOf(RoomMember("h", "Host", 1, "ok"), RoomMember("me", "Me", 2, "ok")), phase = RoomPhase(),
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
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn"))
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

    @Test fun `a listener's transport commands reach neither the room nor the player`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        val before = player.calls.toList()
        connection.sent.clear()
        player.interceptor!!.run {
            onPlay(); onPause(); onSeek(5_000); onNext(); onPrevious(); onSet(listOf(item("2")), 0)
        }
        runCurrent()
        assertThat(connection.sent).isEmpty()
        assertThat(player.calls).isEqualTo(before)
    }

    @Test fun `a listener's add from a track menu is sent as a suggestion`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.sent.clear()
        player.interceptor!!.onAdd(listOf(item("2"))); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Suggest(next))
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

    fun TestScope.host(upcoming: List<MediaItem> = listOf(item("2"))) {
        coEvery { api.create("Rawn") } returns ShareResult.Ok(RoomApiClient.Created("K7QA2PXM", "KEY", "https://x/l/K7QA2PXM"))
        player.queue = UserQueue(current = item("1"), upcoming = upcoming, positionMs = 42_000)
        session()
        controller.send(Command.Host); runCurrent()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", hostKey = "KEY"))
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = null, key = 0)))
    }

    @Test fun `hosting sets the queue aside and sends the user's song and queue to the new room`() = runTest {
        host()
        assertThat(player.calls.take(3)).containsExactly("save", "enter:host", "pause").inOrder()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Load>()).containsExactly(ClientMessage.Load(track, 42_000, listOf(next)))
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", resumeToken = "tok"))
        assertThat((controller.state.value as ListenTogetherState.InRoom).url).isEqualTo("https://x/l/K7QA2PXM")
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
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, emptyList()))
    }

    @Test fun `with the queue empty and autoplay radio on the host starts a station`() = runTest {
        autoplay = true
        catalog.radio = listOf(next, third)
        host(upcoming = emptyList())
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, listOf(third)))
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
        connection.sent.clear()
        player.interceptor!!.onAdd(listOf(item("3"))); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Queue(listOf(next, third)))
    }

    @Test fun `tapping a playlist while hosting plays it for everyone`() = runTest {
        host()
        connection.sent.clear()
        player.interceptor!!.onSet(listOf(item("2"), item("3")), 0); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, listOf(third)))
    }

    @Test fun `a host who leaves hands the room to the longest-joined listener`() = runTest {
        host()
        receive(ServerMessage.Members(listOf(RoomMember("h", "Host", 1), RoomMember("a", "A", 5), RoomMember("b", "B", 3))))
        controller.send(Command.Leave); runCurrent()
        assertThat(connection.sent).contains(ClientMessage.MakeHost("b"))
        assertThat(connection.closed).isTrue()
    }

    @Test fun `end session tells the room and restores the host's own music`() = runTest {
        host()
        controller.send(Command.End); runCurrent()
        assertThat(connection.sent).contains(ClientMessage.End)
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
}
