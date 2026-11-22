package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.model.community.ScheduleWindow
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationSchedule
import com.stash.core.model.listen.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** [SessionAutoLive]: the rules ([com.stash.core.model.community.AutoLive]) wired to playback, the room and the clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionAutoLiveTest {
    private val playing = MutableStateFlow(false)
    private val room = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    private val downloadMode = MutableStateFlow(false)
    private val sessionsOn = MutableStateFlow<Boolean?>(true)
    private val edits = MutableStateFlow(0)
    private var online = true
    private var station: Station? = Station(id = "St4tion1", name = "Rawn's Basement", hostName = "Rawn", mine = true, autoLive = "always")
    private var reads = 0
    private val sent = mutableListOf<Command>()

    /** Monday 2026-11-16 in New York, plus the test's virtual time. */
    private fun base(hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, 16, hour, minute, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private var startAt = base(10, 0)

    private fun TestScope.autoLive() = SessionAutoLive(
        playing = playing, room = room, downloadMode = downloadMode, sessionsOn = sessionsOn, edits = edits,
        myStation = { reads++; station }, online = { online }, send = { sent += it },
        scope = backgroundScope, clock = { startAt + testScheduler.currentTime },
    ).also { it.start(); runCurrent() }

    private fun hosting() = ListenTogetherState.InRoom(
        code = "K7QA2PXM", url = "u", myId = "me", isHost = true, hostId = "me", members = emptyList(), suggestions = emptyList(),
        session = SessionInfo("St4tion1"), role = SessionRole.HOST,
    )

    @Test fun `Always - the music starting puts the station on the air, under its DJ name`() = runTest {
        autoLive()
        assertThat(sent).isEmpty()
        playing.value = true; runCurrent()
        assertThat(sent).containsExactly(Command.GoLive("St4tion1", "Rawn"))
    }

    @Test fun `a station with auto-live off is read once and never ticks`() = runTest {
        station = station!!.copy(autoLive = "off")
        autoLive()
        playing.value = true; runCurrent()
        advanceTimeBy(10 * 60_000); runCurrent()
        assertThat(sent).isEmpty()
        assertThat(reads).isEqualTo(1) // at start (fresh for 5 minutes, so not again as the music starts); nothing on a timer
    }

    @Test fun `no station, and Sessions off, do nothing - and off asks the Worker nothing`() = runTest {
        station = null
        autoLive()
        playing.value = true; runCurrent()
        assertThat(sent).isEmpty()

        sent.clear(); reads = 0
        sessionsOn.value = false
        station = Station(id = "St4tion1", name = "x", autoLive = "always")
        playing.value = false; runCurrent()
        advanceTimeBy(6 * 60_000)
        playing.value = true; runCurrent()
        assertThat(sent).isEmpty()
        assertThat(reads).isEqualTo(0)
    }

    @Test fun `Download mode never goes live`() = runTest {
        downloadMode.value = true
        autoLive()
        playing.value = true; runCurrent()
        assertThat(sent).isEmpty()
    }

    @Test fun `without a network it waits, and goes live at the next look once it's back`() = runTest {
        online = false
        autoLive()
        playing.value = true; runCurrent()
        assertThat(sent).isEmpty()
        online = true
        advanceTimeBy(30_000); runCurrent()
        assertThat(sent).containsExactly(Command.GoLive("St4tion1", "Rawn"))
    }

    @Test fun `a window opening while the music plays goes live, and its end asks for the last song`() = runTest {
        station = station!!.copy(autoLive = "schedule", schedule = StationSchedule("America/New_York", listOf(ScheduleWindow(1, "10:02", "10:05"))))
        autoLive()
        playing.value = true; runCurrent()
        assertThat(sent).isEmpty() // 10:00: not yet
        advanceTimeBy(2 * 60_000 + 1_000); runCurrent() // the look at 10:02
        assertThat(sent).containsExactly(Command.GoLive("St4tion1", "Rawn"))
        room.value = hosting(); runCurrent() // the session opens within the half minute it's given
        advanceTimeBy(3 * 60_000 + 30_000); runCurrent() // past 10:05
        assertThat(sent).containsExactly(Command.GoLive("St4tion1", "Rawn"), Command.LastSong(true)).inOrder()
        advanceTimeBy(5 * 60_000); runCurrent()
        assertThat(sent).hasSize(2) // asked once
    }

    @Test fun `ending the session doesn't go live again while the music plays on`() = runTest {
        autoLive()
        playing.value = true; runCurrent()
        room.value = hosting(); runCurrent()
        room.value = ListenTogetherState.Idle; runCurrent()
        advanceTimeBy(5 * 60_000); runCurrent()
        assertThat(sent).containsExactly(Command.GoLive("St4tion1", "Rawn"))
    }

    @Test fun `an edit to the station is read at once - turning Always on while the music plays goes live`() = runTest {
        station = station!!.copy(autoLive = "off")
        autoLive()
        playing.value = true; runCurrent()
        assertThat(sent).isEmpty()
        station = station!!.copy(autoLive = "always")
        edits.value = 1; runCurrent()
        assertThat(sent).containsExactly(Command.GoLive("St4tion1", "Rawn"))
    }

    @Test fun `the station is read again when the music starts after 5 minutes`() = runTest {
        station = station!!.copy(autoLive = "off")
        autoLive()
        advanceTimeBy(6 * 60_000)
        station = station!!.copy(autoLive = "always") // changed on another phone: no edit seen here
        playing.value = true; runCurrent()
        assertThat(reads).isEqualTo(2)
        assertThat(sent).containsExactly(Command.GoLive("St4tion1", "Rawn"))
    }

    @Test fun `each going live is announced, for the day's first notice`() = runTest {
        val auto = autoLive()
        val seen = mutableListOf<Station>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { auto.wentLive.collect { seen += it } }
        playing.value = true; runCurrent()
        assertThat(seen.map { it.id }).containsExactly("St4tion1")
    }
}
