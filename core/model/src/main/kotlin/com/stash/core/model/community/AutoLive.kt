package com.stash.core.model.community

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Auto-live (Sessions design §6): when a station goes on the air by itself. Pure, so the whole rule table is
 * tested without a phone: windows on the host's wall clock (past midnight, across a clock change), the Always mode,
 * and every reason never to.
 */
object AutoLiveSchedule {

    /** The most windows a schedule holds. */
    const val MAX_WINDOWS = 7

    private fun time(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()

    private fun zone(schedule: StationSchedule): ZoneId? = runCatching { ZoneId.of(schedule.tz) }.getOrNull()

    /** [window]'s run that starts on [date], or null when the window is malformed. An end at or before its start is the next day's. */
    private fun run(window: ScheduleWindow, date: LocalDate, zone: ZoneId): OnAirWindow? {
        val start = time(window.start) ?: return null
        val end = time(window.end) ?: return null
        if (window.day !in 1..7) return null
        val endDate = if (end > start) date else date.plusDays(1)
        return OnAirWindow(
            start = ZonedDateTime.of(date, start, zone).toInstant().toEpochMilli(),
            end = ZonedDateTime.of(endDate, end, zone).toInstant().toEpochMilli(),
        )
    }

    /** Every run of the schedule that starts on one of the days from [from] to [to]. */
    private fun runs(schedule: StationSchedule, zone: ZoneId, from: LocalDate, to: LocalDate): List<OnAirWindow> {
        val out = ArrayList<OnAirWindow>()
        var date = from
        while (!date.isAfter(to)) {
            val day = date.dayOfWeek.value // Monday = 1, as the schedule counts
            for (w in schedule.windows) if (w.day == day) run(w, date, zone)?.let(out::add)
            date = date.plusDays(1)
        }
        return out
    }

    /** The window [nowMs] is inside (the one ending last, if two overlap), or null. A schedule with an unknown zone has none. */
    fun windowAt(schedule: StationSchedule, nowMs: Long): OnAirWindow? {
        val zone = zone(schedule) ?: return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // Yesterday's too: one that runs past midnight is still on.
        return runs(schedule, zone, today.minusDays(1), today).filter { nowMs >= it.start && nowMs < it.end }.maxByOrNull { it.end }
    }

    /** The next window to open after [nowMs], within a week, or null. */
    fun nextWindow(schedule: StationSchedule, nowMs: Long): OnAirWindow? {
        val zone = zone(schedule) ?: return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return runs(schedule, zone, today, today.plusDays(7)).filter { it.start > nowMs }.minByOrNull { it.start }
    }

    /** Whether [window] can be kept: a day of the week, two times, and not the same time twice (that would be a whole day, by accident). */
    fun isValid(window: ScheduleWindow): Boolean {
        val start = time(window.start) ?: return false
        val end = time(window.end) ?: return false
        return window.day in DayOfWeek.MONDAY.value..DayOfWeek.SUNDAY.value && start != end
    }
}

/** A station's auto-live mode, as the server spells it. */
enum class AutoLiveMode(val wire: String) {
    OFF("off"), SCHEDULE("schedule"), ALWAYS("always");

    companion object {
        fun of(wire: String?): AutoLiveMode = entries.firstOrNull { it.wire == wire } ?: OFF
    }
}

/** Where this phone is, as far as going live by itself goes. */
enum class AutoLiveRoom {
    /** Not in a room: free to go live. */
    NONE,

    /** Hosting this station's session. */
    HOSTING,

    /** In a private Listen Together room, or someone else's session: never auto-live from here. */
    ELSEWHERE,
}

/** Everything auto-live looks at, at one moment. */
data class AutoLiveFacts(
    /** This phone's station; null without one. */
    val station: Station?,
    /** Music is playing on this phone. */
    val playing: Boolean,
    val room: AutoLiveRoom,
    /** Download mode: fully offline, never on the air. */
    val downloadMode: Boolean,
    /** Community and Sessions are on. */
    val communityOn: Boolean,
    val online: Boolean,
)

/** What auto-live does now. */
sealed interface AutoLiveStep {
    data object Nothing : AutoLiveStep

    /** Go live with [station]. [until]: its schedule window's end, or null for Always. */
    data class GoLive(val station: Station, val until: Long?) : AutoLiveStep

    /** The window that opened this session is over: end after the song that's playing. */
    data object EndAfterSong : AutoLiveStep
}

/**
 * Auto-live's memory between looks. It goes live on an edge, never on a level: playback starting inside a window
 * (or at all, for Always), or a window opening while music plays. So a host who ends an auto-live session isn't put
 * straight back on the air: the next start of playback, or the next window, is the next chance. If something is in
 * the way at the edge (no network, a private room) it waits, silently, and goes live when that clears while the
 * music is still playing in the same window.
 *
 * Not thread-safe: looked at from one place.
 */
class AutoLive {
    /** Playing inside a window (or Always) at the last look. */
    private var wasEligible = false

    /** An edge is waiting for what was in its way to clear. */
    private var pending = false

    /** The window's end of the session auto-live opened; null when it opened none, or opened one for Always. */
    private var until: Long? = null

    /** Auto-live opened the session this phone is hosting. */
    private var opened = false
    private var endAsked = false

    fun look(facts: AutoLiveFacts, nowMs: Long): AutoLiveStep {
        val station = facts.station
        val mode = AutoLiveMode.of(station?.autoLive)
        val window = if (mode == AutoLiveMode.SCHEDULE) station?.schedule?.let { AutoLiveSchedule.windowAt(it, nowMs) } else null
        val eligible = facts.playing && (mode == AutoLiveMode.ALWAYS || window != null)

        // A session auto-live opened on a schedule ends after the song that's playing when its window closes.
        if (facts.room == AutoLiveRoom.HOSTING) {
            pending = false
            wasEligible = eligible
            val end = until
            if (opened && end != null && nowMs >= end && !endAsked) {
                endAsked = true
                return AutoLiveStep.EndAfterSong
            }
            return AutoLiveStep.Nothing
        }
        // Not hosting any more: whatever auto-live opened is over.
        opened = false
        until = null
        endAsked = false

        if (eligible && !wasEligible) pending = true
        if (!eligible) pending = false
        wasEligible = eligible
        if (!pending || station == null) return AutoLiveStep.Nothing

        val clear = facts.room == AutoLiveRoom.NONE && !facts.downloadMode && facts.communityOn && facts.online &&
            !station.hidden && !station.removed
        if (!clear) return AutoLiveStep.Nothing
        pending = false
        opened = true
        until = window?.end
        return AutoLiveStep.GoLive(station, until)
    }
}
