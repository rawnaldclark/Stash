package com.stash.feature.listening

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words "Your listening" shows: day titles, counts, lengths, times and ages. Pure (no Android),
 * so every label is unit-tested; the screens pass the locale, the zone and the phone's 12/24-hour
 * setting in.
 */
internal object ListeningLabels {

    /** "Today", "Yesterday", "Sat, Oct 11", or "Sat, Oct 11, 2025" outside the current year. */
    fun dayTitle(day: Long, today: Long, locale: Locale = Locale.getDefault()): String = when (day) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> absoluteDay(day, today, locale)
    }

    /**
     * A date without the relative words, for labels that outlive the day they were made on (the
     * "Playing from" line of a saved queue): "Sat, Oct 11" (+ year outside the current one).
     */
    fun absoluteDay(day: Long, today: Long, locale: Locale = Locale.getDefault()): String {
        val date = LocalDate.ofEpochDay(day)
        val pattern = if (date.year == LocalDate.ofEpochDay(today).year) "EEE, MMM d" else "EEE, MMM d, yyyy"
        return date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** "1 play", "12 plays". */
    fun plays(count: Int): String = if (count == 1) "1 play" else "$count plays"

    /**
     * Listening time, rounded to the minute, the one format every listening screen uses: "47 min",
     * "2 h", "2 h 5 min", "1,234 h 5 min". Under a minute reads "1 min" (a day with a listen is
     * never "0 min"); nothing at all reads "0 min".
     */
    fun listenTime(ms: Long): String {
        if (ms <= 0) return "0 min"
        val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "%,d h".format(Locale.US, h)
            else -> "%,d h %d min".format(Locale.US, h, m)
        }
    }

    /** A top row's numbers: "23 plays · 1 h 12 min" ("1 play" when the time is unknown). */
    fun playsAndTime(plays: Int, listenedMs: Long): String =
        if (listenedMs <= 0) plays(plays) else "${plays(plays)} · ${listenTime(listenedMs)}"

    /**
     * The dates a range covers: "Sep 19 – Oct 18", one date for a single day, and the years
     * when the range isn't within the current year ("Dec 2, 2025 – Oct 18, 2026").
     */
    fun rangeCaption(fromDay: Long, toDay: Long, today: Long, locale: Locale = Locale.getDefault()): String {
        val from = LocalDate.ofEpochDay(fromDay)
        val to = LocalDate.ofEpochDay(toDay)
        val thisYear = LocalDate.ofEpochDay(today).year
        val pattern = if (from.year == thisYear && to.year == thisYear) "MMM d" else "MMM d, yyyy"
        val f = DateTimeFormatter.ofPattern(pattern, locale)
        return if (fromDay == toDay) to.format(f) else "${from.format(f)} – ${to.format(f)}"
    }

    /** An hour of the day for the listening clock: "9 PM", "12 AM", or "21:00". */
    fun hour(hour: Int, use24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        LocalTime.of(hour.coerceIn(0, 23), 0).format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h a", locale))

    /** "5-day streak", "1-day streak". */
    fun streak(days: Int): String = "$days-day streak"

    /** The Streak tile's word under its number: "1" + "day streak", "5" + "days streak" (owner wording). */
    fun streakUnit(days: Int): String = if (days == 1) "day streak" else "days streak"

    /** "1 day", "12 days". */
    fun days(count: Int): String = if (count == 1) "1 day" else "$count days"

    /** "1 time", "9 times". */
    fun times(count: Int): String = if (count == 1) "1 time" else "$count times"

    /** A short date for highlights: "Oct 11" (+ year outside the current one). */
    fun shortDay(day: Long, today: Long, locale: Locale = Locale.getDefault()): String {
        val date = LocalDate.ofEpochDay(day)
        val pattern = if (date.year == LocalDate.ofEpochDay(today).year) "MMM d" else "MMM d, yyyy"
        return date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** A day header's line after the title: "12 plays · 47 min"; null when the counts aren't known. */
    fun daySummary(plays: Int?, listenedMs: Long?): String? {
        if (plays == null || plays <= 0) return null
        val time = listenedMs?.takeIf { it > 0 }?.let(::listenTime)
        return if (time == null) plays(plays) else "${plays(plays)} · $time"
    }

    /**
     * The clock time a listen started, "9:41 PM" or "21:41". [localMinute] is the minute stamped
     * where the listener was (v59); without one, [startedAtMs] is read in [zone].
     */
    fun timeOfDay(
        localMinute: Int?,
        startedAtMs: Long,
        use24Hour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val time = if (localMinute != null && localMinute in 0 until MINUTES_PER_DAY) {
            LocalTime.of(localMinute / 60, localMinute % 60)
        } else {
            Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalTime()
        }
        return time.format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale))
    }

    /**
     * Where a listen came from when it wasn't this phone, as a short tag for its row: null for this
     * phone's own. Imports name their service; everything else from another device is Stash on the
     * web (the web player, its mirror, a library file sent from it).
     */
    fun origin(originDevice: String?): String? = when {
        originDevice == null -> null
        originDevice == "import:lastfm" -> "Last.fm"
        originDevice == "import:listenbrainz" -> "ListenBrainz"
        else -> "Web"
    }

    /** A History row's trailing text: "9:41 PM", or "Web · 9:41 PM" for a listen from elsewhere. */
    fun rowTrailing(time: String, origin: String?): String = if (origin == null) time else "$origin · $time"

    /**
     * How long ago, for "Last played …": "just now", "5 min ago", "3 h ago", "yesterday",
     * "4 days ago", then the date ("Oct 11", with the year outside the current one).
     */
    fun ago(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val minutes = ((nowMs - thenMs).coerceAtLeast(0)) / 60_000
        val thenDate = Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = nowDate.toEpochDay() - thenDate.toEpochDay()
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            days == 0L -> "${minutes / 60} h ago"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            thenDate.year == nowDate.year -> thenDate.format(DateTimeFormatter.ofPattern("MMM d", locale))
            else -> thenDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        }
    }

    private const val MINUTES_PER_DAY = 1440
}
