package com.stash.feature.listening.ui.charts

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningStatsCalculator
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HeatmapMonthLabelsTest {

    private fun weeks(from: LocalDate, to: LocalDate, first: DayOfWeek = DayOfWeek.SUNDAY) =
        ListeningStatsCalculator.heatmapWeeks(
            listOf(DayCount(to.toEpochDay(), 1, 1)),
            DayRange(from.toEpochDay(), to.toEpochDay()),
            first,
        )

    private fun months(labels: List<Pair<Int, Long>>) = labels.map { LocalDate.ofEpochDay(it.second).monthValue }

    @Test fun `every month that starts in the grid gets its name, even when its 1st is mid-week`() {
        // Jul 19 - Oct 18 2026, weeks from Sunday: Aug 1 is a Saturday in the week of Jul 26,
        // Oct 1 a Thursday in the week of Sep 27. (The bug: those months were skipped.) "Jul" over
        // the first week would collide with "Aug" one column later, so it's left out.
        val labels = heatmapMonthLabels(weeks(LocalDate.of(2026, 7, 19), LocalDate.of(2026, 10, 18)))
        assertThat(months(labels)).containsExactly(8, 9, 10).inOrder()
        assertThat(labels.map { it.first }).containsExactly(1, 6, 10).inOrder()
        assertThat(labels.map { it.first }).isInOrder()
    }

    @Test fun `the first week is named when no month starts in the next columns`() {
        // Jul 5 - Oct 18: Aug 1 is four columns in, so "Jul" leads.
        val long = heatmapMonthLabels(weeks(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 10, 18)))
        assertThat(months(long)).containsExactly(7, 8, 9, 10).inOrder()
        assertThat(long.first().first).isEqualTo(0)
    }

    @Test fun `no lead label when a month starts in the first column`() {
        // Starts Jul 30: Aug 1 is in the first column, so no separate "Jul".
        val labels = heatmapMonthLabels(weeks(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 10, 30)))
        assertThat(months(labels).first()).isEqualTo(8)
        assertThat(labels.map { it.first }.distinct().size).isEqualTo(labels.size)
    }

    @Test fun `no weeks, no labels`() {
        assertThat(heatmapMonthLabels(emptyList())).isEmpty()
    }
}
