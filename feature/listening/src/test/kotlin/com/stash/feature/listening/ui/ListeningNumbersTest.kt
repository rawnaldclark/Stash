package com.stash.feature.listening.ui

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.util.Locale
import org.junit.Test

/** The week chart's days ([weekBarDays]) and colour ([weekBarColor]), and a time's number/unit pairs ([listenTimeParts]). */
class ListeningNumbersTest {
    /** Sunday 1 November 2026. */
    private val sunday = LocalDate.of(2026, 11, 1).toEpochDay()

    private val week = listOf(0L, 0L, 0L, 0L, 200_000L, 200_000L, 120_000L)

    @Test fun `the bars are the last seven days, oldest first, today last`() {
        val days = weekBarDays(week, sunday, Locale.US)
        assertThat(days.map { it.day }).containsExactlyElementsIn((sunday - 6..sunday).toList()).inOrder()
        assertThat(days.map { it.listenedMs }).containsExactlyElementsIn(week).inOrder()
    }

    @Test fun `each bar is named by its weekday's initial`() {
        val days = weekBarDays(week, sunday, Locale.US)
        assertThat(days.map { it.initial }).containsExactly("M", "T", "W", "T", "F", "S", "S").inOrder()
    }

    @Test fun `only today is today`() {
        val days = weekBarDays(week, sunday, Locale.US)
        assertThat(days.map { it.isToday }).containsExactly(false, false, false, false, false, false, true).inOrder()
    }

    @Test fun `the initials follow today, whatever day it is`() {
        val wednesday = LocalDate.of(2026, 11, 4).toEpochDay()
        val days = weekBarDays(week, wednesday, Locale.US)
        assertThat(days.map { it.initial }).containsExactly("T", "F", "S", "S", "M", "T", "W").inOrder()
        assertThat(days.last().isToday).isTrue()
    }

    @Test fun `the initials are in the app's language`() {
        val days = weekBarDays(week, sunday, Locale.FRANCE)
        assertThat(days.map { it.initial }).containsExactly("L", "M", "M", "J", "V", "S", "D").inOrder()
    }

    @Test fun `a negative time draws as an empty day`() {
        val days = weekBarDays(listOf(-5L, 60_000L), sunday, Locale.US)
        assertThat(days.map { it.listenedMs }).containsExactly(0L, 60_000L).inOrder()
        assertThat(days.map { it.day }).containsExactly(sunday - 1, sunday).inOrder()
    }

    @Test fun `no bars, no days`() {
        assertThat(weekBarDays(emptyList(), sunday, Locale.US)).isEmpty()
    }

    @Test fun `the bars are neutral with nothing playing and exactly the album colour while a song plays`() {
        val neutral = Color(0xFFE8E8F0)
        val accent = Color(0xFF3FA7D6)
        assertThat(weekBarColor(neutral, accent, tint = 0f)).isEqualTo(neutral)
        assertThat(weekBarColor(neutral, accent, tint = 1f)).isEqualTo(accent)
        // Outside 0..1 (an overshooting fade) counts as the nearer end.
        assertThat(weekBarColor(neutral, accent, tint = -0.2f)).isEqualTo(neutral)
        assertThat(weekBarColor(neutral, accent, tint = 1.3f)).isEqualTo(accent)
    }

    @Test fun `while the album colour fades in, the bars are in between`() {
        val neutral = Color(0xFFFFFFFF)
        val accent = Color(0xFF0000FF)
        val half = weekBarColor(neutral, accent, tint = 0.5f)
        assertThat(half).isNotEqualTo(neutral)
        assertThat(half).isNotEqualTo(accent)
        // Toward blue: the red falls, the blue stays full.
        assertThat(half.red).isLessThan(neutral.red)
        assertThat(half.red).isGreaterThan(accent.red)
        val later = weekBarColor(neutral, accent, tint = 0.8f)
        assertThat(later.red).isLessThan(half.red)
    }

    @Test fun `a time splits into numbers and their units`() {
        assertThat(listenTimeParts("47 min")).containsExactly("47" to "min")
        assertThat(listenTimeParts("2 h")).containsExactly("2" to "h")
        assertThat(listenTimeParts("2 h 5 min")).containsExactly("2" to "h", "5" to "min").inOrder()
        assertThat(listenTimeParts("1,234 h 5 min")).containsExactly("1,234" to "h", "5" to "min").inOrder()
        assertThat(listenTimeParts("0 min")).containsExactly("0" to "min")
    }

    @Test fun `text without a number comes back whole`() {
        assertThat(listenTimeParts("soon")).containsExactly("" to "soon")
        assertThat(listenTimeParts("")).containsExactly("" to "")
    }
}
