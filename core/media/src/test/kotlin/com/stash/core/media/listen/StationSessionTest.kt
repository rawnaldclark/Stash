package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.listen.RoomEvent
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherSessionTest.FakeConnection
import com.stash.core.media.listen.ListenTogetherSessionTest.FakePlayer
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomMember
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

    companion object {
        const val STATION = "Ab3dEf9h"
        val KEY = "k".repeat(43)
    }
}
