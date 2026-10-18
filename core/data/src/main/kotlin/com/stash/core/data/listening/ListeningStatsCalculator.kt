package com.stash.core.data.listening

import com.stash.core.data.db.dao.DayCount
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The numbers Stats and Your Year show, worked out from the per-day counts
 * ([com.stash.core.data.db.dao.ListeningHistoryDao.observeDayCounts]) in plain Kotlin: no SQL, no
 * clock, no zone — every input is an epoch-day, so it is exact and cheap to test. A few thousand
 * days at most, so linear passes are fine.
 */
object ListeningStatsCalculator {

    /** A run of consecutive days with at least one listen, [fromDay]..[toDay] inclusive. */
    data class Streak(val days: Int, val fromDay: Long, val toDay: Long)

    enum class Granularity { DAY, WEEK, MONTH }

    /** One point of the listens chart: [fromDay]..[toDay] inclusive, zero when nothing was heard. */
    data class Bucket(val fromDay: Long, val toDay: Long, val plays: Int, val listenedMs: Long)

    /** One heatmap square; [level] 0 = no listens, 1..4 = more listens relative to the busiest day shown. */
    data class HeatCell(val day: Long, val plays: Int, val level: Int)

    const val HEAT_LEVELS = 4

    /**
     * The streak still running: consecutive listening days ending today, or ending yesterday when
     * nothing has been heard yet today (the day isn't over, so the streak isn't broken). Null when
     * neither today nor yesterday has a listen.
     */
    fun currentStreak(days: Collection<DayCount>, today: Long): Streak? {
        val heard = heardDays(days)
        val end = when {
            today in heard -> today
            today - 1 in heard -> today - 1
            else -> return null
        }
        var start = end
        while (start - 1 in heard) start--
        return Streak(days = (end - start + 1).toInt(), fromDay = start, toDay = end)
    }

    /** The longest run of consecutive listening days (the most recent one on a tie), or null. */
    fun longestStreak(days: Collection<DayCount>): Streak? {
        val sorted = heardDays(days).sorted()
        if (sorted.isEmpty()) return null
        var best = Streak(1, sorted[0], sorted[0])
        var runStart = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] != sorted[i - 1] + 1) runStart = sorted[i]
            val len = (sorted[i] - runStart + 1).toInt()
            if (len >= best.days) best = Streak(len, runStart, sorted[i])
        }
        return best
    }

    /** The day with the most listens; on a tie the one with more listening time, then the later day. */
    fun busiestDay(days: Collection<DayCount>): DayCount? =
        days.filter { it.plays > 0 }
            .maxWithOrNull(compareBy<DayCount> { it.plays }.thenBy { it.listenedMs }.thenBy { it.day })

    /** Days for up to a month, weeks for up to a year, months beyond. */
    fun granularityFor(range: DayRange): Granularity = when {
        range.length <= 31 -> Granularity.DAY
        range.length <= 366 -> Granularity.WEEK
        else -> Granularity.MONTH
    }

    /**
     * The chart series for [range]: every bucket from the first to the last day, empty ones
     * included, so the line has no gaps. Weeks start on [firstDayOfWeek] and months on the 1st; the
     * first and last bucket are cut to the range. Listens outside the range are ignored.
     */
    fun bucketSeries(
        days: Collection<DayCount>,
        range: DayRange,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        granularity: Granularity = granularityFor(range),
    ): List<Bucket> {
        val byDay = days.associateBy { it.day }
        val out = ArrayList<Bucket>()
        var start = range.fromDay
        while (start <= range.toDay) {
            val date = LocalDate.ofEpochDay(start)
            val naturalEnd = when (granularity) {
                Granularity.DAY -> start
                Granularity.WEEK -> date.with(TemporalAdjusters.nextOrSame(firstDayOfWeek.minus(1))).toEpochDay()
                Granularity.MONTH -> date.with(TemporalAdjusters.lastDayOfMonth()).toEpochDay()
            }
            val end = minOf(naturalEnd, range.toDay)
            var plays = 0
            var ms = 0L
            for (d in start..end) {
                val c = byDay[d] ?: continue
                plays += c.plays
                ms += c.listenedMs
            }
            out += Bucket(start, end, plays, ms)
            start = end + 1
        }
        return out
    }

    /**
     * The day heatmap: one column per week, oldest first, seven cells per column starting on
     * [firstDayOfWeek]. It ends with the week of [range]'s last day and shows at least [minWeeks]
     * weeks (a short range is widened backwards, so the grid never looks empty for a new user).
     * Cells after the range's last day (the rest of this week) are null; so are cells before its
     * first day when the range itself is longer than [minWeeks].
     */
    fun heatmapWeeks(
        days: Collection<DayCount>,
        range: DayRange,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        minWeeks: Int = 13,
    ): List<List<HeatCell?>> {
        val lastWeekStart = weekStart(range.toDay, firstDayOfWeek)
        val naturalStart = weekStart(range.fromDay, firstDayOfWeek)
        val widenedStart = lastWeekStart - (minWeeks - 1) * 7L
        val firstWeekStart = minOf(naturalStart, widenedStart)
        // Widened: every day of the extra weeks shows (empty or not). Otherwise the days of the
        // first week before the range starts are blank.
        val shownFrom = if (widenedStart < naturalStart) widenedStart else range.fromDay
        val byDay = days.associateBy { it.day }
        val max = days.filter { it.day in shownFrom..range.toDay }.maxOfOrNull { it.plays } ?: 0
        val weeks = ArrayList<List<HeatCell?>>()
        var ws = firstWeekStart
        while (ws <= lastWeekStart) {
            weeks += (0 until 7).map { i ->
                val d = ws + i
                if (d < shownFrom || d > range.toDay) {
                    null
                } else {
                    val plays = byDay[d]?.plays ?: 0
                    HeatCell(d, plays, levelOf(plays, max))
                }
            }
            ws += 7
        }
        return weeks
    }

    /** 0 for no listens, else 1..[HEAT_LEVELS] in equal steps up to [max]. */
    fun levelOf(plays: Int, max: Int): Int {
        if (plays <= 0 || max <= 0) return 0
        val level = (plays.toLong() * HEAT_LEVELS + max - 1) / max // ceil(plays * 4 / max)
        return level.toInt().coerceIn(1, HEAT_LEVELS)
    }

    /** The first day of the week [locale] uses (Monday in most of Europe, Sunday in the US). */
    fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    private fun weekStart(day: Long, firstDayOfWeek: DayOfWeek): Long =
        LocalDate.ofEpochDay(day).with(TemporalAdjusters.previousOrSame(firstDayOfWeek)).toEpochDay()

    private fun heardDays(days: Collection<DayCount>): Set<Long> =
        days.asSequence().filter { it.plays > 0 }.mapTo(HashSet()) { it.day }
}
