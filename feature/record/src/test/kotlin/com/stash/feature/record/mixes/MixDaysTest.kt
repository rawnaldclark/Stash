package com.stash.feature.record.mixes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Home's few change at midnight: the days flow says when the local day or the UTC day turns, and only then. */
class MixDaysTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun `a new pair when the UTC day turns, then when the local day does, never in between`() = runTest {
        var clock = at(2026, 10, 27, 23, 58)
        val zone = ZoneId.of("America/New_York") // UTC−4 on these days
        val seen = mutableListOf<Pair<Long, Long>>()
        val job = launch { mixDays(now = { clock }, zone = { zone }).take(3).toList(seen) }
        runCurrent()
        // 19:58 in New York on the 27th, 23:58 UTC on the 27th
        assertThat(seen).hasSize(1)
        clock = at(2026, 10, 28, 0, 0) // the UTC day turns; New York still the 27th
        advanceTimeBy(60_001)
        assertThat(seen).hasSize(2)
        clock = at(2026, 10, 28, 3, 59) // nothing turns: nothing said
        advanceTimeBy(60_001)
        assertThat(seen).hasSize(2)
        clock = at(2026, 10, 28, 4, 0) // midnight in New York
        advanceTimeBy(60_001)
        job.join()
        val day27 = java.time.LocalDate.of(2026, 10, 27).toEpochDay()
        assertThat(seen).containsExactly(day27 to day27, day27 to day27 + 1, (day27 + 1) to (day27 + 1)).inOrder()
    }
}
