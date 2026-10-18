package com.stash.feature.listening

import com.google.common.truth.Truth.assertThat
import com.stash.feature.listening.FakeHistory.Companion.TODAY
import com.stash.feature.listening.history.calendarLevel
import com.stash.feature.listening.history.calendarThresholds
import com.stash.feature.listening.history.indexOfDay
import com.stash.feature.listening.history.monthsBetween
import com.stash.feature.listening.history.HistoryDay
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

class ListeningLabelsTest {

    private val us = Locale.US
    private val day = 86_400_000L

    @Test fun `day titles are relative, then dated, with the year only outside this one`() {
        assertThat(ListeningLabels.dayTitle(TODAY, TODAY, us)).isEqualTo("Today")
        assertThat(ListeningLabels.dayTitle(TODAY - 1, TODAY, us)).isEqualTo("Yesterday")
        assertThat(ListeningLabels.dayTitle(LocalDate.of(2026, 10, 11).toEpochDay(), TODAY, us)).isEqualTo("Sun, Oct 11")
        assertThat(ListeningLabels.dayTitle(LocalDate.of(2025, 10, 11).toEpochDay(), TODAY, us)).isEqualTo("Sat, Oct 11, 2025")
        assertThat(ListeningLabels.absoluteDay(TODAY, TODAY, us)).isEqualTo("Sun, Oct 18")
    }

    @Test fun `counts and listening time read naturally`() {
        assertThat(ListeningLabels.plays(1)).isEqualTo("1 play")
        assertThat(ListeningLabels.plays(12)).isEqualTo("12 plays")
        assertThat(ListeningLabels.listenTime(10_000)).isEqualTo("1 min")
        assertThat(ListeningLabels.listenTime(47 * 60_000L)).isEqualTo("47 min")
        assertThat(ListeningLabels.listenTime(120 * 60_000L)).isEqualTo("2 h")
        assertThat(ListeningLabels.listenTime(125 * 60_000L + 20_000)).isEqualTo("2 h 5 min")
        assertThat(ListeningLabels.daySummary(12, 47 * 60_000L)).isEqualTo("12 plays · 47 min")
        assertThat(ListeningLabels.daySummary(3, 0)).isEqualTo("3 plays")
        assertThat(ListeningLabels.daySummary(null, null)).isNull()
        assertThat(ListeningLabels.daySummary(0, 0)).isNull()
    }

    @Test fun `the time of a listen comes from its stamped minute, else its start in the zone`() {
        assertThat(ListeningLabels.timeOfDay(21 * 60 + 41, 0, use24Hour = false, locale = us)).isEqualTo("9:41 PM")
        assertThat(ListeningLabels.timeOfDay(21 * 60 + 41, 0, use24Hour = true, locale = us)).isEqualTo("21:41")
        val startedAt = TODAY * day + (7 * 60 + 5) * 60_000L
        assertThat(ListeningLabels.timeOfDay(null, startedAt, use24Hour = true, zone = ZoneOffset.UTC, locale = us)).isEqualTo("07:05")
    }

    @Test fun `other devices get a short tag, this phone none`() {
        assertThat(ListeningLabels.origin(null)).isNull()
        assertThat(ListeningLabels.origin("import:lastfm")).isEqualTo("Last.fm")
        assertThat(ListeningLabels.origin("import:listenbrainz")).isEqualTo("ListenBrainz")
        assertThat(ListeningLabels.origin("web-1234")).isEqualTo("Web")
        assertThat(ListeningLabels.rowTrailing("9:41 PM", null)).isEqualTo("9:41 PM")
        assertThat(ListeningLabels.rowTrailing("9:41 PM", "Web")).isEqualTo("Web · 9:41 PM")
    }

    @Test fun `ages go from just now to a date`() {
        val now = TODAY * day + 12 * 3_600_000L
        val utc = ZoneOffset.UTC
        assertThat(ListeningLabels.ago(now - 20_000, now, utc, us)).isEqualTo("just now")
        assertThat(ListeningLabels.ago(now - 5 * 60_000L, now, utc, us)).isEqualTo("5 min ago")
        assertThat(ListeningLabels.ago(now - 3 * 3_600_000L, now, utc, us)).isEqualTo("3 h ago")
        assertThat(ListeningLabels.ago(now - day, now, utc, us)).isEqualTo("yesterday")
        assertThat(ListeningLabels.ago(now - 4 * day, now, utc, us)).isEqualTo("4 days ago")
        assertThat(ListeningLabels.ago(now - 30 * day, now, utc, us)).isEqualTo("Sep 18")
        assertThat(ListeningLabels.ago(now - 400 * day, now, utc, us)).isEqualTo("Sep 13, 2025")
    }

    // ── Calendar ─────────────────────────────────────────────────────

    @Test fun `the calendar runs from the first listen's month to this one`() {
        val first = LocalDate.of(2026, 8, 30).toEpochDay()
        assertThat(monthsBetween(first, TODAY)).containsExactly(
            YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10),
        ).inOrder()
        assertThat(monthsBetween(TODAY, TODAY)).containsExactly(YearMonth.of(2026, 10))
    }

    @Test fun `days fill by quartile of the user's own days`() {
        val t = calendarThresholds(listOf(1, 2, 3, 4, 5, 6, 7, 8, 0))
        assertThat(calendarLevel(0, t)).isEqualTo(0)
        assertThat(calendarLevel(1, t)).isEqualTo(1)
        assertThat(calendarLevel(8, t)).isEqualTo(4)
        assertThat((1..8).map { calendarLevel(it, t) }).isInOrder()
        // Every day the same: all one level, none empty-looking.
        val same = calendarThresholds(listOf(3, 3, 3))
        assertThat(calendarLevel(3, same)).isEqualTo(1)
        assertThat(calendarThresholds(emptyList()).toList()).containsExactly(0, 0, 0)
    }

    @Test fun `a day's index counts the headers and rows above it`() {
        val days = listOf(
            HistoryDay(TODAY, "a", List(3) { stubRow() }),
            HistoryDay(TODAY - 2, "b", List(2) { stubRow() }),
            HistoryDay(TODAY - 5, "c", List(1) { stubRow() }),
        )
        assertThat(indexOfDay(days, TODAY, leading = 1)).isEqualTo(1)
        assertThat(indexOfDay(days, TODAY - 2, leading = 1)).isEqualTo(5)
        // A day without listens lands on the next older listed day.
        assertThat(indexOfDay(days, TODAY - 3, leading = 1)).isEqualTo(8)
        assertThat(indexOfDay(days, Long.MAX_VALUE, leading = 1)).isEqualTo(0)
    }

    @Test fun `a key's index matches the list's order, leading cards included`() {
        val rows = List(3) { stubRow() }
        val state = com.stash.feature.listening.history.HistoryUiState(
            days = listOf(HistoryDay(TODAY, "a", rows.take(2)), HistoryDay(TODAY - 1, "b", rows.drop(2))),
            paused = true,
            loading = false,
            dayCounts = mapOf(TODAY to com.stash.core.data.db.dao.DayCount(TODAY, 2, 0)),
        )
        assertThat(com.stash.feature.listening.history.indexOfKey(state, "paused")).isEqualTo(0)
        assertThat(com.stash.feature.listening.history.indexOfKey(state, "a")).isEqualTo(1)
        assertThat(com.stash.feature.listening.history.indexOfKey(state, "e${rows[1].event.id}")).isEqualTo(3)
        assertThat(com.stash.feature.listening.history.indexOfKey(state, "b")).isEqualTo(4)
        assertThat(com.stash.feature.listening.history.indexOfKey(state, "gone")).isNull()
    }

    @Test fun `keyAt is indexOfKey's inverse over the whole list`() {
        val rows = List(4) { stubRow() }
        val state = com.stash.feature.listening.history.HistoryUiState(
            days = listOf(HistoryDay(TODAY, "a", rows.take(3)), HistoryDay(TODAY - 1, "b", rows.drop(3))),
            paused = true,
            loading = false,
        )
        for (i in 0 until 7) {
            val key = com.stash.feature.listening.history.keyAt(state, i)!!
            assertThat(com.stash.feature.listening.history.indexOfKey(state, key)).isEqualTo(i)
        }
        assertThat(com.stash.feature.listening.history.keyAt(state, 7)).isNull()
    }

    private val fake = FakeHistory()
    private fun stubRow() = fake.add(fake.track(1), TODAY, 0)
}
