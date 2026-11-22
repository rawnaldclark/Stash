package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.community.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** [AutoLiveNotifier]: the day's first auto-live says so once, and the notice goes when the session ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoLiveNotifierTest {
    private val wentLive = MutableSharedFlow<Station>(extraBufferCapacity = 4)
    private val hosting = MutableStateFlow(false)
    private val station = Station(id = "St4tion1", name = "Rawn's Basement", hostName = "Rawn", mine = true, autoLive = "always")
    private val zone = ZoneId.of("America/New_York")
    private var now = at(16, 10, 0)
    private var day = AutoLiveNotifier.NO_DAY
    private val log = mutableListOf<String>()

    private fun at(dayOfMonth: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, dayOfMonth, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private val notices = object : AutoLiveNotices {
        override fun onAir(station: Station) { log += "on air ${station.name}" }
        override fun clear() { log += "clear" }
    }

    /** Auto-live asks, and the room opens. */
    private fun TestScope.goLive() {
        wentLive.tryEmit(station); runCurrent()
        hosting.value = true; runCurrent()
    }

    private fun TestScope.notifier() = AutoLiveNotifier(
        wentLive = wentLive, hosting = hosting, notices = notices, lastDay = { day }, saveDay = { day = it },
        scope = backgroundScope, clock = { now }, zone = { zone },
    ).also { it.start(); runCurrent() }

    @Test fun `the day's first auto-live says so, and later ones that day say nothing`() = runTest {
        notifier()
        assertThat(log).isEmpty()
        goLive()
        assertThat(log).containsExactly("on air Rawn's Basement")
        hosting.value = false; runCurrent()
        now = at(16, 23, 59)
        goLive()
        assertThat(log).containsExactly("on air Rawn's Basement", "clear").inOrder()
    }

    @Test fun `the next day it says so again, by the phone's own calendar`() = runTest {
        notifier()
        now = at(16, 23, 59)
        goLive()
        hosting.value = false; runCurrent()
        now = at(17, 0, 1) // two minutes later, but tomorrow
        goLive()
        assertThat(log).containsExactly("on air Rawn's Basement", "clear", "on air Rawn's Basement").inOrder()
    }

    @Test fun `the day told is remembered across a restart`() = runTest {
        day = AutoLiveNotifier.dayOf(now, zone) // told earlier today, before the app was restarted
        notifier()
        goLive()
        assertThat(log).isEmpty()
    }

    @Test fun `going live that never reached the air says nothing, and doesn't use up the day`() = runTest {
        notifier()
        wentLive.emit(station); runCurrent() // asked, but the room never opened
        assertThat(log).isEmpty()
        assertThat(day).isEqualTo(AutoLiveNotifier.NO_DAY)
        goLive()
        assertThat(log).containsExactly("on air Rawn's Basement")
    }

    @Test fun `a session started by hand long after a failed auto-live isn't called auto-live's`() = runTest {
        notifier()
        wentLive.emit(station); runCurrent()
        now += AutoLiveNotifier.OPENING_MS + 1
        hosting.value = true; runCurrent()
        assertThat(log).isEmpty()
    }

    @Test fun `the notice goes when the session ends, and only if one was shown`() = runTest {
        notifier()
        hosting.value = true; runCurrent()
        hosting.value = false; runCurrent()
        assertThat(log).isEmpty() // a session started by hand: nothing to clear

        goLive()
        assertThat(log).containsExactly("on air Rawn's Basement")
        hosting.value = false; runCurrent()
        assertThat(log).containsExactly("on air Rawn's Basement", "clear").inOrder()
    }

    @Test fun `a day is the calendar day in the phone's zone`() {
        val ny = AutoLiveNotifier.dayOf(at(16, 23, 30), zone)
        assertThat(AutoLiveNotifier.dayOf(at(16, 0, 0), zone)).isEqualTo(ny)
        assertThat(AutoLiveNotifier.dayOf(at(17, 0, 0), zone)).isEqualTo(ny + 1)
        // The same instant is already tomorrow in Tokyo.
        assertThat(AutoLiveNotifier.dayOf(at(16, 23, 30), ZoneId.of("Asia/Tokyo"))).isEqualTo(ny + 1)
    }
}
