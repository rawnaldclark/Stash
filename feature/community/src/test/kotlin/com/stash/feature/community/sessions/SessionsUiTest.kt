package com.stash.feature.community.sessions

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.OnAirWindow
import com.stash.core.model.community.Station
import com.stash.core.model.share.SharedTrack
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Test

class SessionsUiTest {
    private fun station(id: String, name: String = "Station $id") = Station(id = id, name = name)
    private fun session(id: String) = LiveSession(station = station(id), room = "ROOM0001")
    private fun state(on: Boolean = true, live: List<LiveSession>? = emptyList(), following: List<Station> = emptyList()) =
        SessionsViewModel.UiState(on = on, live = live, following = following)

    @Test fun `nothing shows until Sessions is on and the list has loaded`() {
        assertThat(sessionsRows(state(on = false, live = listOf(session("AAAAAAAA"))), asked = 10)).isNull()
        assertThat(sessionsRows(state(live = null), asked = 10)).isNull()
        assertThat(sessionsRows(SessionsViewModel.UiState(), asked = 10)).isNull()
    }

    @Test fun `on with nobody live, the rows are there and empty`() {
        val rows = sessionsRows(state(), asked = 10)!!
        assertThat(rows.live).isEmpty()
        assertThat(rows.yourStations).isEmpty()
        assertThat(rows.liveCount).isEqualTo(0)
    }

    @Test fun `the rows keep the order they were given`() {
        val live = listOf(session("CCCCCCCC"), session("AAAAAAAA"))
        val following = listOf(station("ZZZZZZZZ"), station("BBBBBBBB"))
        val rows = sessionsRows(state(live = live, following = following), asked = 10)!!
        assertThat(rows.live).containsExactlyElementsIn(live).inOrder()
        assertThat(rows.yourStations).containsExactlyElementsIn(following).inOrder()
    }

    @Test fun `the count is said only when the list can't have been cut short`() {
        val nine = List(9) { session("STATION$it") }
        assertThat(sessionsRows(state(live = nine), asked = 10)!!.liveCount).isEqualTo(9)
        // Ten came back from a request for ten: there may be more, so no number is claimed.
        assertThat(sessionsRows(state(live = nine + session("STATION9")), asked = 10)!!.liveCount).isNull()
    }

    @Test fun `the song is one line, and a song without an artist has no stray dot`() {
        assertThat(SharedTrack("Pink + White", "Frank Ocean").nowLine()).isEqualTo("Pink + White · Frank Ocean")
        assertThat(SharedTrack("Untitled", "").nowLine()).isEqualTo("Untitled")
    }

    @Test fun `a station's face is its name's first letter or digit`() {
        assertThat(station("AAAAAAAA", "maya's desk radio").initial()).isEqualTo("M")
        assertThat(station("AAAAAAAA", "  “late” night").initial()).isEqualTo("L")
        assertThat(station("AAAAAAAA", "808s only").initial()).isEqualTo("8")
        assertThat(station("AAAAAAAA", "♫♫").initial()).isEqualTo("?")
    }

    private val newYork = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, newYork).toInstant().toEpochMilli()

    @Test fun `next on the air is the time alone today, and with its day after that`() {
        val now = at(20, 7) // a Friday morning
        val hour = 3_600_000L
        assertThat(onAirLabel(OnAirWindow(at(20, 8, 30), at(20, 17)), now, newYork, Locale.US)).isEqualTo("8:30 AM")
        assertThat(onAirLabel(OnAirWindow(at(21, 21), at(21, 23)), now, newYork, Locale.US)).isEqualTo("Sat 9:00 PM")
        // The phone's own zone and language, not the host's.
        assertThat(onAirLabel(OnAirWindow(at(20, 8, 30), at(20, 17)), now, ZoneId.of("Europe/Berlin"), Locale.GERMANY)).isEqualTo("14:30")
        // A window in progress, or none: nothing (a station on the air says LIVE).
        assertThat(onAirLabel(OnAirWindow(now - hour, now + hour), now, newYork, Locale.US)).isNull()
        assertThat(onAirLabel(null, now, newYork, Locale.US)).isNull()
    }
}
