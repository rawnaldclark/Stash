package com.stash.core.model.community

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Auto-live's rule table: the windows, and when a station goes on the air by itself. */
class AutoLiveTest {
    private val ny = "America/New_York"

    /** A moment on New York's wall clock, in ms. 2026-11-16 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 11, zone: String = ny): Long =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun schedule(vararg windows: ScheduleWindow, tz: String = ny) = StationSchedule(tz, windows.toList())
    private val weekday9to5 = ScheduleWindow(day = 1, start = "09:00", end = "17:00")

    // ── windows ─────────────────────────────────────────────────────────

    @Test fun `inside a window from its first minute to just before its end, on its day only`() {
        val s = schedule(weekday9to5)
        assertThat(AutoLiveSchedule.windowAt(s, at(16, 8, 59))).isNull()
        assertThat(AutoLiveSchedule.windowAt(s, at(16, 9, 0))).isEqualTo(OnAirWindow(at(16, 9), at(16, 17)))
        assertThat(AutoLiveSchedule.windowAt(s, at(16, 16, 59))).isNotNull()
        assertThat(AutoLiveSchedule.windowAt(s, at(16, 17, 0))).isNull()
        assertThat(AutoLiveSchedule.windowAt(s, at(17, 12))).isNull() // Tuesday
    }

    @Test fun `a window past midnight runs into the next day`() {
        val s = schedule(ScheduleWindow(day = 5, start = "22:00", end = "02:00")) // Friday night
        assertThat(AutoLiveSchedule.windowAt(s, at(20, 23))).isEqualTo(OnAirWindow(at(20, 22), at(21, 2)))
        assertThat(AutoLiveSchedule.windowAt(s, at(21, 1, 59))).isEqualTo(OnAirWindow(at(20, 22), at(21, 2)))
        assertThat(AutoLiveSchedule.windowAt(s, at(21, 2))).isNull()
        assertThat(AutoLiveSchedule.windowAt(s, at(21, 23))).isNull() // Saturday night isn't Friday's
    }

    @Test fun `Sunday night runs into Monday`() {
        val s = schedule(ScheduleWindow(day = 7, start = "23:00", end = "01:00"))
        assertThat(AutoLiveSchedule.windowAt(s, at(23, 0, 30))).isEqualTo(OnAirWindow(at(22, 23), at(23, 1)))
    }

    @Test fun `the hours are the host's wall clock, across the week the clocks change`() {
        // US clocks go back on Sunday 2026-11-01: 9:00 is 13:00 UTC the Monday before and 14:00 UTC the Monday after.
        val s = schedule(weekday9to5)
        val before = AutoLiveSchedule.windowAt(s, at(26, 10, month = 10))!!
        val after = AutoLiveSchedule.windowAt(s, at(2, 10))!!
        assertThat(after.start - before.start).isEqualTo(7 * 24 * 3_600_000L + 3_600_000L)
        assertThat(after.end - after.start).isEqualTo(8 * 3_600_000L)
        // A listener's phone in another zone gets the same instants: the zone travels with the schedule.
        assertThat(AutoLiveSchedule.windowAt(s, at(16, 15, zone = "Europe/London"))).isEqualTo(OnAirWindow(at(16, 9), at(16, 17)))
    }

    @Test fun `the next window is the soonest one still to open, within a week`() {
        val s = schedule(weekday9to5, ScheduleWindow(day = 3, start = "20:00", end = "22:00"))
        assertThat(AutoLiveSchedule.nextWindow(s, at(16, 8))).isEqualTo(OnAirWindow(at(16, 9), at(16, 17)))
        assertThat(AutoLiveSchedule.nextWindow(s, at(16, 10))).isEqualTo(OnAirWindow(at(18, 20), at(18, 22)))
        assertThat(AutoLiveSchedule.nextWindow(s, at(18, 21))).isEqualTo(OnAirWindow(at(23, 9), at(23, 17)))
        assertThat(AutoLiveSchedule.nextWindow(schedule(), at(16, 8))).isNull()
    }

    @Test fun `a schedule that can't be read has no windows`() {
        assertThat(AutoLiveSchedule.windowAt(schedule(weekday9to5, tz = "Mars/Olympus"), at(16, 10))).isNull()
        assertThat(AutoLiveSchedule.windowAt(schedule(ScheduleWindow(1, "nine", "17:00")), at(16, 10))).isNull()
        assertThat(AutoLiveSchedule.windowAt(schedule(ScheduleWindow(8, "09:00", "17:00")), at(16, 10))).isNull()
    }

    @Test fun `a window needs a day, two times, and two different ones`() {
        assertThat(AutoLiveSchedule.isValid(weekday9to5)).isTrue()
        assertThat(AutoLiveSchedule.isValid(ScheduleWindow(5, "22:00", "02:00"))).isTrue()
        assertThat(AutoLiveSchedule.isValid(ScheduleWindow(1, "09:00", "09:00"))).isFalse()
        assertThat(AutoLiveSchedule.isValid(ScheduleWindow(0, "09:00", "10:00"))).isFalse()
        assertThat(AutoLiveSchedule.isValid(ScheduleWindow(1, "9", "10:00"))).isFalse()
    }

    // ── going live ──────────────────────────────────────────────────────

    private fun station(mode: String, schedule: StationSchedule? = null, hidden: Boolean = false, removed: Boolean = false) =
        Station(id = "St4tion1", name = "Rawn's Basement", mine = true, autoLive = mode, schedule = schedule, hidden = hidden, removed = removed)

    private val scheduled = station("schedule", schedule(weekday9to5))
    private val always = station("always")

    private fun facts(
        station: Station? = scheduled,
        playing: Boolean = true,
        room: AutoLiveRoom = AutoLiveRoom.NONE,
        downloadMode: Boolean = false,
        communityOn: Boolean = true,
        online: Boolean = true,
    ) = AutoLiveFacts(station, playing, room, downloadMode, communityOn, online)

    @Test fun `playback starting inside a window goes live until the window's end`() {
        val auto = AutoLive()
        assertThat(auto.look(facts(playing = false), at(16, 10))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(facts(), at(16, 10, 1))).isEqualTo(AutoLiveStep.GoLive(scheduled, until = at(16, 17)))
    }

    @Test fun `a window opening while music plays goes live`() {
        val auto = AutoLive()
        assertThat(auto.look(facts(), at(16, 8, 59))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(facts(), at(16, 9, 0))).isEqualTo(AutoLiveStep.GoLive(scheduled, until = at(16, 17)))
    }

    @Test fun `outside its windows a scheduled station stays off the air`() {
        val auto = AutoLive()
        assertThat(auto.look(facts(playing = false), at(16, 20))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(facts(), at(16, 20, 1))).isEqualTo(AutoLiveStep.Nothing)
    }

    @Test fun `Always goes live on any playback, with no end`() {
        val auto = AutoLive()
        auto.look(facts(station = always, playing = false), at(16, 3))
        assertThat(auto.look(facts(station = always), at(16, 3, 1))).isEqualTo(AutoLiveStep.GoLive(always, until = null))
    }

    @Test fun `Off, and no station at all, never go live`() {
        assertThat(AutoLive().look(facts(station = station("off", schedule(weekday9to5))), at(16, 10))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(AutoLive().look(facts(station = null), at(16, 10))).isEqualTo(AutoLiveStep.Nothing)
    }

    @Test fun `it goes live once - ending the session doesn't put the host straight back on the air`() {
        val auto = AutoLive()
        assertThat(auto.look(facts(station = always), at(16, 10))).isInstanceOf(AutoLiveStep.GoLive::class.java)
        assertThat(auto.look(facts(station = always, room = AutoLiveRoom.HOSTING), at(16, 10, 1))).isEqualTo(AutoLiveStep.Nothing)
        // The host ends it; the music plays on.
        assertThat(auto.look(facts(station = always), at(16, 10, 5))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(facts(station = always), at(16, 10, 30))).isEqualTo(AutoLiveStep.Nothing)
        // Playback stops and starts again: the next chance.
        auto.look(facts(station = always, playing = false), at(16, 11))
        assertThat(auto.look(facts(station = always), at(16, 11, 1))).isInstanceOf(AutoLiveStep.GoLive::class.java)
    }

    @Test fun `each never - Download mode, a room elsewhere, Community off, a hidden or removed station`() {
        val t = at(16, 10)
        assertThat(AutoLive().look(facts(downloadMode = true), t)).isEqualTo(AutoLiveStep.Nothing)
        assertThat(AutoLive().look(facts(room = AutoLiveRoom.ELSEWHERE), t)).isEqualTo(AutoLiveStep.Nothing)
        assertThat(AutoLive().look(facts(communityOn = false), t)).isEqualTo(AutoLiveStep.Nothing)
        assertThat(AutoLive().look(facts(station = scheduled.copy(hidden = true)), t)).isEqualTo(AutoLiveStep.Nothing)
        assertThat(AutoLive().look(facts(station = scheduled.copy(removed = true)), t)).isEqualTo(AutoLiveStep.Nothing)
    }

    @Test fun `without a network it waits, and goes live when it's back while the music still plays`() {
        val auto = AutoLive()
        assertThat(auto.look(facts(online = false), at(16, 10))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(facts(online = false), at(16, 10, 30))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(facts(), at(16, 11))).isEqualTo(AutoLiveStep.GoLive(scheduled, until = at(16, 17)))
    }

    @Test fun `what was waiting is dropped when the music stops, or the window closes`() {
        val stopped = AutoLive()
        stopped.look(facts(online = false), at(16, 10))
        stopped.look(facts(online = false, playing = false), at(16, 10, 30))
        assertThat(stopped.look(facts(playing = false), at(16, 11))).isEqualTo(AutoLiveStep.Nothing)

        val closed = AutoLive()
        closed.look(facts(room = AutoLiveRoom.ELSEWHERE), at(16, 16, 59))
        assertThat(closed.look(facts(), at(16, 17, 1))).isEqualTo(AutoLiveStep.Nothing)
    }

    @Test fun `leaving a private room inside a window goes live, since the music was already playing`() {
        val auto = AutoLive()
        assertThat(auto.look(facts(room = AutoLiveRoom.ELSEWHERE), at(16, 10))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(facts(), at(16, 10, 30))).isEqualTo(AutoLiveStep.GoLive(scheduled, until = at(16, 17)))
    }

    @Test fun `at the window's end the session ends after the song, asked once`() {
        val auto = AutoLive()
        auto.look(facts(), at(16, 16, 50))
        val hosting = facts(room = AutoLiveRoom.HOSTING)
        assertThat(auto.look(hosting, at(16, 16, 59))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(auto.look(hosting, at(16, 17, 0))).isEqualTo(AutoLiveStep.EndAfterSong)
        assertThat(auto.look(hosting, at(16, 17, 1))).isEqualTo(AutoLiveStep.Nothing)
    }

    @Test fun `a session the host opened by hand, or an Always one, is never ended for them`() {
        val byHand = AutoLive()
        assertThat(byHand.look(facts(room = AutoLiveRoom.HOSTING), at(16, 16, 59))).isEqualTo(AutoLiveStep.Nothing)
        assertThat(byHand.look(facts(room = AutoLiveRoom.HOSTING), at(16, 17, 5))).isEqualTo(AutoLiveStep.Nothing)

        val forever = AutoLive()
        forever.look(facts(station = always), at(16, 10))
        assertThat(forever.look(facts(station = always, room = AutoLiveRoom.HOSTING), at(17, 10))).isEqualTo(AutoLiveStep.Nothing)
    }

    @Test fun `a session opened by hand inside a window doesn't count as the window's chance used`() {
        // Hosting by hand at 9:00 when the window opens, then ending at 10: the music plays on, and that is not an edge.
        val auto = AutoLive()
        auto.look(facts(room = AutoLiveRoom.HOSTING), at(16, 8, 59))
        auto.look(facts(room = AutoLiveRoom.HOSTING), at(16, 9, 1))
        assertThat(auto.look(facts(), at(16, 10))).isEqualTo(AutoLiveStep.Nothing)
    }
}
