package com.stash.core.data.listening

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where in the listener's own calendar a listen happened: [day] = days since 1970-01-01
 * (`LocalDate.toEpochDay()`), [minute] = 0..1439 into that day. Both in the zone the phone was in.
 */
data class LocalStamp(val day: Long, val minute: Int)

/**
 * The one place a listen's local day and minute are worked out. Every path that writes a
 * `listening_events` row stamps it here (ListeningRecorder, WebLibraryImporter,
 * RoomMirrorLibrary.addPlays, ListeningDayBackfill, the debug seeder), so they never disagree.
 *
 * Why stored instead of computed in SQL: SQLite's `'localtime'` uses the phone's zone *now*, so
 * after a flight every past listen shifts a day and "Today" or a streak changes under the user.
 * A listen keeps the day of the place it was heard. A listen that crosses midnight belongs to the
 * day it started (callers pass the start time).
 */
object ListeningDay {

    /** Milliseconds in a UTC day: `started_at / MS_PER_DAY` is the fallback day of an unstamped row. */
    const val MS_PER_DAY: Long = 86_400_000L

    /**
     * The local day and minute of [epochMs] in [zone]. DST-safe: the minute is read off the local
     * wall clock, so on a 23- or 25-hour day it is still 0..1439 (a repeated hour reads twice, a
     * skipped hour never appears), and zones with :30 / :45 offsets (Asia/Kolkata, Pacific/Chatham)
     * land on the right minute.
     */
    fun localStamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalStamp {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
        return LocalStamp(day = local.toLocalDate().toEpochDay(), minute = local.hour * 60 + local.minute)
    }

    /** Today's epoch-day in [zone]. */
    fun today(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long =
        localStamp(nowMs, zone).day

    /** The calendar date of an epoch-day. */
    fun dateOf(day: Long): LocalDate = LocalDate.ofEpochDay(day)
}
