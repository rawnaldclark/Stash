package com.stash.feature.listening.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listening.ListeningRange
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Which recap Home's door and Your listening's banner offer, and the period's words. */
class RecapAvailabilityTest {

    private fun month(y: Int, m: Int) = RecapPeriod.MonthOf(y, m)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun `mid month the door offers this month so far`() {
        assertThat(RecapAvailability.doorCandidates(d(2026, 10, 18))).containsExactly(month(2026, 10))
        assertThat(RecapAvailability.doorCandidates(d(2026, 10, 8))).containsExactly(month(2026, 10))
    }

    @Test fun `a month's first week offers last month first, then this month`() {
        assertThat(RecapAvailability.doorCandidates(d(2026, 11, 1))).containsExactly(month(2026, 10), month(2026, 11)).inOrder()
        assertThat(RecapAvailability.doorCandidates(d(2026, 11, 7))).containsExactly(month(2026, 10), month(2026, 11)).inOrder()
    }

    @Test fun `December 1 to January 15 the year comes first`() {
        assertThat(RecapAvailability.doorCandidates(d(2026, 11, 30)).first()).isEqualTo(month(2026, 11))
        assertThat(RecapAvailability.doorCandidates(d(2026, 12, 1)))
            .containsExactly(RecapPeriod.YearOf(2026), month(2026, 11), month(2026, 12)).inOrder()
        assertThat(RecapAvailability.doorCandidates(d(2026, 12, 20)))
            .containsExactly(RecapPeriod.YearOf(2026), month(2026, 12)).inOrder()
        // January: the year just ended, and December in the first week.
        assertThat(RecapAvailability.doorCandidates(d(2027, 1, 3)))
            .containsExactly(RecapPeriod.YearOf(2026), month(2026, 12), month(2027, 1)).inOrder()
        assertThat(RecapAvailability.doorCandidates(d(2027, 1, 15)).first()).isEqualTo(RecapPeriod.YearOf(2026))
        assertThat(RecapAvailability.doorCandidates(d(2027, 1, 16))).containsExactly(month(2027, 1))
    }

    @Test fun `the first candidate with twenty listens wins`() = runTest {
        val plays = mapOf<RecapPeriod, Int>(month(2026, 10) to 19, month(2026, 11) to 20)
        val c = RecapAvailability.doorCandidates(d(2026, 11, 3))
        assertThat(RecapAvailability.firstQualifying(c) { plays[it] ?: 0 }).isEqualTo(month(2026, 11))
        assertThat(RecapAvailability.firstQualifying(c) { 5 }).isNull()
        // A year with too few listens falls back to the months.
        val dec = RecapAvailability.doorCandidates(d(2026, 12, 10))
        assertThat(RecapAvailability.firstQualifying(dec) { if (it is RecapPeriod.YearOf) 3 else 25 }).isEqualTo(month(2026, 12))
    }

    @Test fun `the banner offers the year for 12 months and All time`() {
        assertThat(RecapAvailability.bannerCandidates(d(2026, 10, 18), ListeningRange.Months12)).containsExactly(RecapPeriod.YearOf(2026))
        assertThat(RecapAvailability.bannerCandidates(d(2027, 1, 10), ListeningRange.AllTime)).containsExactly(RecapPeriod.YearOf(2026))
        assertThat(RecapAvailability.bannerCandidates(d(2026, 10, 18), ListeningRange.Days30)).containsExactly(month(2026, 10))
        assertThat(RecapAvailability.bannerCandidates(d(2026, 12, 3), ListeningRange.Days7))
            .containsExactly(month(2026, 11), month(2026, 12)).inOrder()
    }

    @Test fun `titles say so far while the period is running`() {
        val today = d(2026, 10, 18).toEpochDay()
        assertThat(month(2026, 10).title(today, Locale.US)).isEqualTo("Your October so far")
        assertThat(month(2026, 9).title(today, Locale.US)).isEqualTo("Your September")
        assertThat(RecapPeriod.YearOf(2026).title(today)).isEqualTo("Your 2026 so far")
        assertThat(RecapPeriod.YearOf(2026).title(d(2026, 12, 2).toEpochDay())).isEqualTo("Your 2026")
        assertThat(RecapPeriod.YearOf(2025).title(today)).isEqualTo("Your 2025")
        assertThat(month(2026, 10).longName(Locale.US)).isEqualTo("October 2026")
    }

    @Test fun `periods round-trip through the route`() {
        assertThat(RecapPeriod.fromRoute("month", 2026, 10)).isEqualTo(month(2026, 10))
        assertThat(RecapPeriod.fromRoute("year", 2026, 0)).isEqualTo(RecapPeriod.YearOf(2026))
        assertThat(RecapPeriod.fromRoute("month", 2026, 13)).isNull()
        assertThat(RecapPeriod.fromRoute("month", 0, 3)).isNull()
        assertThat(RecapPeriod.fromRoute("week", 2026, 1)).isNull()
        val p = month(2026, 2)
        assertThat(RecapPeriod.fromRoute(p.kind, p.year, p.monthArg)).isEqualTo(p)
    }

    @Test fun `December and January boundaries of month ranges`() {
        val jan15 = d(2027, 1, 15).toEpochDay()
        val dec = month(2026, 12).dayRange(jan15)
        assertThat(dec.fromDay).isEqualTo(d(2026, 12, 1).toEpochDay())
        assertThat(dec.toDay).isEqualTo(d(2026, 12, 31).toEpochDay())
        assertThat(RecapPeriod.YearOf(2026).dayRange(jan15).length).isEqualTo(365)
        assertThat(month(2027, 1).inProgress(jan15)).isTrue()
        assertThat(month(2026, 12).inProgress(jan15)).isFalse()
    }

    @Test fun `past recaps list every month with twenty listens, newest first, then finished years`() {
        fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
        val counts = listOf(
            day(2025, 12, 3) to 25, // a qualifying month in a finished year
            day(2026, 8, 9) to 12, day(2026, 8, 20) to 7, // August: 19, not enough
            day(2026, 9, 2) to 40,
            day(2026, 10, 18) to 21, // this month so far
        )
        val past = RecapAvailability.pastRecaps(counts, d(2026, 10, 18))
        assertThat(past.map { it.period }).containsExactly(
            month(2026, 10), month(2026, 9), month(2025, 12), RecapPeriod.YearOf(2025),
        ).inOrder()
        assertThat(past.first().plays).isEqualTo(21)
        // This year isn't finished: no "2026" tile yet.
        assertThat(past.map { it.period }).doesNotContain(RecapPeriod.YearOf(2026))
    }

    @Test fun `door subtitles use a short time`() {
        assertThat(shortTime(47 * 60_000L)).isEqualTo("47 min")
        assertThat(shortTime(11 * 3_600_000L + 20 * 60_000L)).isEqualTo("11 h")
        assertThat(shortTime(11 * 3_600_000L + 40 * 60_000L)).isEqualTo("11 h")
    }
}
