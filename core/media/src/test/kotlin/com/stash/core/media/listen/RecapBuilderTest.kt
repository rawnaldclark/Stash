package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.listen.RecapSong
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.SessionInfo
import com.stash.core.model.listen.SessionRecap
import com.stash.core.model.listen.SongStats
import com.stash.core.model.share.SharedTrack
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A session's recap, built from the room's states ([RecapBuilder]) and kept when the stay ends ([SessionRecapRecorder]). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecapBuilderTest {
    private val creep = SharedTrack("Creep", "Radiohead", addedBy = "h")
    private val mine = SharedTrack("Nights", "Frank Ocean", addedBy = "me")
    private val bens = SharedTrack("Ivy", "Frank Ocean", addedBy = "b")
    private val min = 60_000L

    private fun room(
        track: SharedTrack? = creep,
        host: Boolean = false,
        stats: SongStats? = null,
        onAir: Boolean = true,
        listeners: Int = 2,
        members: List<RoomMember> = listOf(RoomMember("h", "Maya"), RoomMember("me", "Rawn"), RoomMember("b", "Ben", regular = true)),
        code: String = "K7QA2PXM",
        session: SessionInfo? = SessionInfo("St4tion1", listeners = listeners),
    ) = ListenTogetherState.InRoom(
        code = code, url = "u", myId = "me", isHost = host, hostId = if (host) "me" else "h", members = members,
        suggestions = emptyList(), track = track, session = session, role = if (host) SessionRole.HOST else SessionRole.LISTENER,
        songStats = stats, onAir = onAir,
    )

    // ── the builder ─────────────────────────────────────────────────────

    @Test fun `a listener's recap - songs, people, their pick and how the room took it, favourites, saves`() {
        val b = RecapBuilder(room(), startedAt = 0)
        b.on(room(stats = SongStats(1, reactions = 4, saves = 2, favourite = true)), 2 * min)
        b.saved()
        b.saved() // the heart tapped twice: once in the list
        b.on(room(track = mine), 4 * min)
        b.on(room(track = mine, stats = SongStats(2, reactions = 3, saves = 1), members = listOf(RoomMember("h", "Maya"), RoomMember("me", "Rawn"), RoomMember("x", "Jo"))), 5 * min)
        b.on(room(track = bens), 8 * min)
        val recap = b.finish(12 * min, ended = true)!!

        assertThat(recap.id).isEqualTo("K7QA2PXM-0")
        assertThat(recap.stationId).isEqualTo("St4tion1")
        assertThat(recap.hostName).isEqualTo("Maya")
        assertThat(recap.wasHost).isFalse()
        assertThat(recap.songs).isEqualTo(3)
        assertThat(recap.people).isEqualTo(3) // Maya, Ben, Jo: never this phone
        assertThat(recap.listenedMs).isEqualTo(12 * min)
        assertThat(recap.myPicks).containsExactly(RecapSong("Nights", "Frank Ocean", reactions = 3, saves = 1))
        assertThat(recap.favourites).containsExactly(RecapSong("Creep", "Radiohead", reactions = 4, saves = 2))
        assertThat(recap.saved).containsExactly(RecapSong("Creep", "Radiohead"))
        assertThat(recap.requestsPlayed).isEqualTo(0) // a host's number
    }

    @Test fun `only time on air counts as listened`() {
        val b = RecapBuilder(room(onAir = false), startedAt = 0)
        b.on(room(onAir = true), 1 * min)
        b.on(room(onAir = false), 4 * min) // paused here, or reconnecting
        b.on(room(onAir = true), 9 * min)
        assertThat(b.finish(11 * min, ended = true)!!.listenedMs).isEqualTo(5 * min)
    }

    @Test fun `a host's recap - time on the air, the most at once, regulars, requests played, the most saved`() {
        val hosts = creep.copy(addedBy = "me")
        val b = RecapBuilder(room(track = hosts, host = true, listeners = 1), startedAt = 0)
        b.on(room(track = hosts, host = true, listeners = 7, stats = SongStats(1, reactions = 1, saves = 1)), 3 * min)
        b.on(room(track = bens, host = true, listeners = 4), 4 * min)
        b.on(room(track = bens, host = true, listeners = 4, stats = SongStats(2, reactions = 5, saves = 3, favourite = true)), 6 * min)
        val recap = b.finish(8 * min, ended = true)!!

        assertThat(recap.wasHost).isTrue()
        assertThat(recap.listenedMs).isEqualTo(8 * min)
        assertThat(recap.peak).isEqualTo(7)
        assertThat(recap.regulars).isEqualTo(1) // Ben
        assertThat(recap.requestsPlayed).isEqualTo(1) // Ben's; the host's own song isn't one
        assertThat(recap.mostSaved).isEqualTo(RecapSong("Ivy", "Frank Ocean", reactions = 5, saves = 3))
        assertThat(recap.myPicks).isEmpty()
        assertThat(recap.favourites.map { it.title }).containsExactly("Ivy")
    }

    @Test fun `a listener who left early gets none, one who stayed 10 minutes or to the end does`() {
        assertThat(RecapBuilder(room(), 0).finish(9 * min, ended = false)).isNull()
        assertThat(RecapBuilder(room(), 0).finish(10 * min, ended = false)).isNotNull()
        assertThat(RecapBuilder(room(), 0).finish(1 * min, ended = true)).isNotNull()
    }

    @Test fun `a session that never played a song has no recap, a host's included`() {
        assertThat(RecapBuilder(room(track = null), 0).finish(30 * min, ended = true)).isNull()
        assertThat(RecapBuilder(room(track = null, host = true), 0).finish(30 * min, ended = true)).isNull()
        assertThat(RecapBuilder(room(host = true), 0).finish(1 * min, ended = true)).isNotNull()
    }

    // ── the recorder ────────────────────────────────────────────────────

    private val state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    private val controller: ListenTogetherController = mockk(relaxed = true) {
        every { state } returns this@RecapBuilderTest.state
        var left = false
        every { userLeft } answers { left }
        every { userLeft = any() } answers { left = firstArg() }
    }
    private val kept = mutableListOf<SessionRecap>()
    private var now = 0L

    @Test fun `a session that ends around the listener is kept and offered, with the station's name`() = runTest {
        val recorder = SessionRecapRecorder(controller, { kept += it }, backgroundScope, { now })
        recorder.start(); runCurrent()
        recorder.noteStation("St4tion1", "Maya's Desk Radio")
        state.value = room(); runCurrent()
        now = 3 * min
        recorder.saved()
        state.value = room(track = mine); runCurrent()
        now = 5 * min
        state.value = ListenTogetherState.Idle; runCurrent()

        val recap = recorder.ended.value!!
        assertThat(recap.stationName).isEqualTo("Maya's Desk Radio")
        assertThat(recap.songs).isEqualTo(2)
        assertThat(recap.saved.map { it.title }).containsExactly("Creep")
        assertThat(kept).containsExactly(recap)
        recorder.shown()
        assertThat(recorder.ended.value).isNull()
    }

    @Test fun `a listener who chose to leave after 10 minutes isn't shown one, but it is kept`() = runTest {
        val recorder = SessionRecapRecorder(controller, { kept += it }, backgroundScope, { now })
        recorder.start(); runCurrent()
        state.value = room(); runCurrent()
        now = 11 * min
        controller.userLeft = true
        state.value = ListenTogetherState.Idle; runCurrent()
        assertThat(recorder.ended.value).isNull()
        assertThat(kept).hasSize(1)
        assertThat(controller.userLeft).isFalse() // read once: the next session starts clean
    }

    @Test fun `leaving after two minutes keeps nothing`() = runTest {
        val recorder = SessionRecapRecorder(controller, { kept += it }, backgroundScope, { now })
        recorder.start(); runCurrent()
        state.value = room(); runCurrent()
        now = 2 * min
        controller.userLeft = true
        state.value = ListenTogetherState.Idle; runCurrent()
        assertThat(kept).isEmpty()
        assertThat(recorder.ended.value).isNull()
    }

    @Test fun `a private room has no recap, and a move to another session closes the first`() = runTest {
        val recorder = SessionRecapRecorder(controller, { kept += it }, backgroundScope, { now })
        recorder.start(); runCurrent()
        state.value = room(session = null); runCurrent()
        now = 20 * min
        state.value = ListenTogetherState.Idle; runCurrent()
        assertThat(kept).isEmpty()

        state.value = room(code = "FIRST001"); runCurrent()
        now = 25 * min
        state.value = room(code = "SECOND02"); runCurrent()
        assertThat(kept.map { it.id }).containsExactly("FIRST001-${20 * min}")
    }
}
