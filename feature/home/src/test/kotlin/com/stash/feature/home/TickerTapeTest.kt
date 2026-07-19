package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TickerTapeTest {

    private fun shoutouts(n: Int) = (1..n).map {
        TickerSegment.Shoutout(name = "S$it", amount = "$$it", message = "m$it")
    }

    @Test
    fun `announcement lands after every third supporter, cycling`() {
        val tape = buildTickerSegments(
            shoutouts(7),
            announcements = listOf("A", "B", "C"),
            every = 3,
        )
        val kinds = tape.map { if (it is TickerSegment.Announcement) it.text else "s" }
        // 3 supporters, A, 3 supporters, B, 1 supporter, then C appended so
        // the full station voice rides every cycle.
        assertThat(kinds)
            .containsExactly("s", "s", "s", "A", "s", "s", "s", "B", "s", "C")
            .inOrder()
    }

    @Test
    fun `all announcements appear even with fewer supporters than the cadence`() {
        val tape = buildTickerSegments(
            shoutouts(2),
            announcements = listOf("A", "B", "C"),
            every = 3,
        )
        val announcements = tape.filterIsInstance<TickerSegment.Announcement>().map { it.text }
        assertThat(announcements).containsExactly("A", "B", "C").inOrder()
    }

    @Test
    fun `supporter order is preserved`() {
        val tape = buildTickerSegments(shoutouts(5))
        val names = tape.filterIsInstance<TickerSegment.Shoutout>().map { it.name }
        assertThat(names).containsExactly("S1", "S2", "S3", "S4", "S5").inOrder()
    }

    @Test
    fun `offset wraps at tape width and resumes proportionally`() {
        // 10 px/s over a 100 px tape: 5 s in = 50 px, 12 s in = 20 px (wrapped).
        assertThat(marqueeOffsetPx(5_000, 10f, 100)).isWithin(0.01f).of(50f)
        assertThat(marqueeOffsetPx(12_000, 10f, 100)).isWithin(0.01f).of(20f)
        // Unmeasured tape is safe.
        assertThat(marqueeOffsetPx(5_000, 10f, 0)).isEqualTo(0f)
    }

    @Test
    fun `offset stays precise after hours of uptime`() {
        val eightHoursMs = 8L * 3600 * 1000
        val offset = marqueeOffsetPx(eightHoursMs, 28f * 3.5f, 40_000)
        assertThat(offset).isAtLeast(0f)
        assertThat(offset).isLessThan(40_000f)
    }
}
