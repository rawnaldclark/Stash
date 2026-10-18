package com.stash.core.data.listening

import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.listening.ListeningStatsCalculator.Bucket
import com.stash.core.data.listening.ListeningStatsCalculator.Granularity
import com.stash.core.data.listening.ListeningStatsCalculator.Streak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/** Pure math over per-day counts: streaks, busiest day, chart buckets, heatmap; plus [ListeningRange]. */
class ListeningStatsCalculatorTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day).toEpochDay()
    private fun dc(day: Long, plays: Int, ms: Long = plays * 180_000L) = DayCount(day, plays, ms)

    private val today = d(2026, 10, 18) // a Sunday

    // ── Streaks ──────────────────────────────────────────────────────

    @Test fun `current streak counts back from today`() {
        val days = listOf(dc(today, 2), dc(today - 1, 1), dc(today - 2, 5), dc(today - 4, 1))
        assertEquals(Streak(3, today - 2, today), ListeningStatsCalculator.currentStreak(days, today))
    }

    @Test fun `current streak keeps yesterday's run while today is still empty`() {
        val days = listOf(dc(today - 1, 1), dc(today - 2, 1))
        assertEquals(Streak(2, today - 2, today - 1), ListeningStatsCalculator.currentStreak(days, today))
    }

    @Test fun `current streak is broken after a whole day without music`() {
        assertNull(ListeningStatsCalculator.currentStreak(listOf(dc(today - 2, 3)), today))
        assertNull(ListeningStatsCalculator.currentStreak(emptyList(), today))
        // A day present with zero plays is no listening day.
        assertNull(ListeningStatsCalculator.currentStreak(listOf(dc(today, 0), dc(today - 1, 0)), today))
    }

    @Test fun `longest streak, the most recent on a tie, order of input irrelevant`() {
        val days = listOf(dc(100, 1), dc(101, 1), dc(102, 1), dc(110, 1), dc(111, 1), dc(112, 1), dc(120, 1)).shuffled()
        assertEquals(Streak(3, 110, 112), ListeningStatsCalculator.longestStreak(days))
        assertEquals(Streak(1, 5, 5), ListeningStatsCalculator.longestStreak(listOf(dc(5, 1))))
        assertNull(ListeningStatsCalculator.longestStreak(emptyList()))
    }

    // ── Busiest day ──────────────────────────────────────────────────

    @Test fun `busiest day by plays, then listening time, then the later day`() {
        assertEquals(10L, ListeningStatsCalculator.busiestDay(listOf(dc(9, 3), dc(10, 7), dc(11, 5)))?.day)
        assertEquals(9L, ListeningStatsCalculator.busiestDay(listOf(dc(9, 7, 900), dc(10, 7, 800)))?.day)
        assertEquals(10L, ListeningStatsCalculator.busiestDay(listOf(dc(9, 7, 800), dc(10, 7, 800)))?.day)
        assertNull(ListeningStatsCalculator.busiestDay(listOf(dc(9, 0))))
    }

    // ── Buckets ──────────────────────────────────────────────────────

    @Test fun `granularity is days up to a month, weeks up to a year, months beyond`() {
        assertEquals(Granularity.DAY, ListeningStatsCalculator.granularityFor(DayRange(today - 30, today)))
        assertEquals(Granularity.WEEK, ListeningStatsCalculator.granularityFor(DayRange(today - 31, today)))
        assertEquals(Granularity.WEEK, ListeningStatsCalculator.granularityFor(DayRange(today - 365, today)))
        assertEquals(Granularity.MONTH, ListeningStatsCalculator.granularityFor(DayRange(today - 366, today)))
    }

    @Test fun `daily buckets include the empty days`() {
        val range = DayRange(today - 6, today)
        val series = ListeningStatsCalculator.bucketSeries(listOf(dc(today - 6, 2, 10), dc(today, 1, 5), dc(today - 20, 9)), range)
        assertEquals(7, series.size)
        assertEquals(Bucket(today - 6, today - 6, 2, 10), series.first())
        assertEquals(Bucket(today, today, 1, 5), series.last())
        assertEquals(3, series.sumOf { it.plays }) // the listen outside the range is ignored
        assertTrue(series.drop(1).dropLast(1).all { it.plays == 0 })
    }

    @Test fun `weekly buckets align to the locale's first day and cut at the range`() {
        // Thu 2026-10-01 .. Sun 2026-10-18, weeks starting Monday: [1..4], [5..11], [12..18].
        val range = DayRange(d(2026, 10, 1), today)
        val days = listOf(dc(d(2026, 10, 4), 1), dc(d(2026, 10, 5), 2), dc(d(2026, 10, 11), 3), dc(today, 4))
        val series = ListeningStatsCalculator.bucketSeries(days, range, DayOfWeek.MONDAY, Granularity.WEEK)
        assertEquals(
            listOf(
                Bucket(d(2026, 10, 1), d(2026, 10, 4), 1, 180_000),
                Bucket(d(2026, 10, 5), d(2026, 10, 11), 5, 900_000),
                Bucket(d(2026, 10, 12), today, 4, 720_000),
            ),
            series,
        )
        // Sunday-first weeks: [1..3], [4..10], [11..17], [18].
        val sunday = ListeningStatsCalculator.bucketSeries(days, range, DayOfWeek.SUNDAY, Granularity.WEEK)
        assertEquals(listOf(d(2026, 10, 1), d(2026, 10, 4), d(2026, 10, 11), today), sunday.map { it.fromDay })
        assertEquals(listOf(0, 3, 3, 4), sunday.map { it.plays })
    }

    @Test fun `monthly buckets run on calendar months`() {
        val range = DayRange(d(2025, 11, 20), d(2026, 2, 3))
        val series = ListeningStatsCalculator.bucketSeries(listOf(dc(d(2026, 1, 31), 6)), range, granularity = Granularity.MONTH)
        assertEquals(listOf(d(2025, 11, 20), d(2025, 12, 1), d(2026, 1, 1), d(2026, 2, 1)), series.map { it.fromDay })
        assertEquals(listOf(d(2025, 11, 30), d(2025, 12, 31), d(2026, 1, 31), d(2026, 2, 3)), series.map { it.toDay })
        assertEquals(listOf(0, 0, 6, 0), series.map { it.plays })
    }

    // ── Heatmap ──────────────────────────────────────────────────────

    @Test fun `heatmap shows at least 13 weeks ending with this week, future days blank`() {
        val weeks = ListeningStatsCalculator.heatmapWeeks(listOf(dc(today, 4), dc(today - 1, 1)), DayRange(today - 6, today), DayOfWeek.MONDAY)
        assertEquals(13, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        // Today is Sunday = the last cell of the last week (Monday-first): nothing after it.
        val last = weeks.last()
        assertEquals(today, last[6]?.day)
        assertEquals(4, last[6]?.level)
        assertEquals(1, last[5]?.level) // 1 of max 4 -> level 1
        // The widened weeks are shown in full, as empty days.
        assertEquals(0, weeks.first()[0]?.plays)
        assertEquals(DayOfWeek.MONDAY, LocalDate.ofEpochDay(weeks.first()[0]!!.day).dayOfWeek)

        // Sunday-first: today is the first cell of its week, the other six are the future.
        val sunday = ListeningStatsCalculator.heatmapWeeks(listOf(dc(today, 4)), DayRange(today - 6, today), DayOfWeek.SUNDAY)
        assertEquals(today, sunday.last()[0]?.day)
        assertTrue(sunday.last().drop(1).all { it == null })
    }

    @Test fun `heatmap over a long range blanks the days before it starts`() {
        val range = DayRange(d(2026, 1, 1), today) // a Thursday
        val weeks = ListeningStatsCalculator.heatmapWeeks(emptyList(), range, DayOfWeek.MONDAY)
        assertTrue(weeks.size > 13)
        assertEquals(listOf(null, null, null), weeks.first().take(3))
        assertEquals(d(2026, 1, 1), weeks.first()[3]?.day)
    }

    @Test fun `heat levels step up to the busiest day`() {
        assertEquals(0, ListeningStatsCalculator.levelOf(0, 10))
        assertEquals(1, ListeningStatsCalculator.levelOf(1, 10))
        assertEquals(1, ListeningStatsCalculator.levelOf(2, 10))
        assertEquals(2, ListeningStatsCalculator.levelOf(5, 10))
        assertEquals(3, ListeningStatsCalculator.levelOf(7, 10))
        assertEquals(4, ListeningStatsCalculator.levelOf(10, 10))
        assertEquals(0, ListeningStatsCalculator.levelOf(3, 0))
    }

    @Test fun `first day of week follows the locale`() {
        assertEquals(DayOfWeek.SUNDAY, ListeningStatsCalculator.firstDayOfWeek(Locale.US))
        assertEquals(DayOfWeek.MONDAY, ListeningStatsCalculator.firstDayOfWeek(Locale.GERMANY))
    }

    // ── ListeningRange ───────────────────────────────────────────────

    @Test fun `rolling ranges end today`() {
        assertEquals(DayRange(today, today), ListeningRange.Today.toDayRange(today))
        assertEquals(DayRange(today - 6, today), ListeningRange.Days7.toDayRange(today))
        assertEquals(DayRange(today - 29, today), ListeningRange.Days30.toDayRange(today))
        assertEquals(DayRange(d(2026, 7, 19), today), ListeningRange.Months3.toDayRange(today))
        assertEquals(DayRange(d(2026, 4, 19), today), ListeningRange.Months6.toDayRange(today))
        assertEquals(DayRange(d(2025, 10, 19), today), ListeningRange.Months12.toDayRange(today))
        assertEquals(DayRange(d(2024, 2, 3), today), ListeningRange.AllTime.toDayRange(today, d(2024, 2, 3)))
        assertEquals(DayRange(today, today), ListeningRange.AllTime.toDayRange(today, null))
        assertEquals(DayRange(5, 9), ListeningRange.Custom(5, 9).toDayRange(today))
    }

    @Test fun `month ranges survive short months`() {
        val may31 = d(2026, 5, 31)
        // Three months before May 31 is Feb 28 (clamped); the range starts the day after.
        assertEquals(DayRange(d(2026, 3, 1), may31), ListeningRange.Months3.toDayRange(may31))
    }

    @Test fun `previous period has the same length and ends the day before`() {
        assertEquals(DayRange(today - 13, today - 7), ListeningRange.Days7.previous(today))
        assertEquals(DayRange(today - 1, today - 1), ListeningRange.Today.previous(today))
        val m3 = ListeningRange.Months3.toDayRange(today)
        val prev = ListeningRange.Months3.previous(today)!!
        assertEquals(m3.length, prev.length)
        assertEquals(m3.fromDay - 1, prev.toDay)
        assertNull(ListeningRange.AllTime.previous(today, 3))
    }

    @Test fun `keys round-trip and bad keys are rejected`() {
        (ListeningRange.PRESETS + ListeningRange.Custom(3, 30)).forEach {
            assertEquals(it, ListeningRange.fromKey(it.key))
        }
        assertNull(ListeningRange.fromKey(null))
        assertNull(ListeningRange.fromKey("2w"))
        assertNull(ListeningRange.fromKey("custom:9:3"))
        assertNull(ListeningRange.fromKey("custom:x:3"))
        assertNull(ListeningRange.fromKey("custom:1:2:3"))
    }

    @Test fun `a year is cut at today while it is in progress`() {
        assertEquals(DayRange(d(2026, 1, 1), today), ListeningRange.year(2026, today))
        assertEquals(DayRange(d(2025, 1, 1), d(2025, 12, 31)), ListeningRange.year(2025, today))
        assertEquals(DayRange(d(2027, 1, 1), d(2027, 1, 1)), ListeningRange.year(2027, today))
    }
}
