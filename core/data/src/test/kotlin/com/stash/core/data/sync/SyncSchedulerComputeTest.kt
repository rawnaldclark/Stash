package com.stash.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

class SyncSchedulerComputeTest {

    private val zone = ZoneId.of("UTC")

    private fun clockAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Clock {
        val instant = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()
        return Clock.fixed(instant, zone)
    }

    private val scheduler = SyncScheduler(
        context = org.mockito.kotlin.mock(),
        syncStateManager = org.mockito.kotlin.mock(),
    )

    @Test fun `daily mask, current is Mon 10am, target 6am — schedules Tue 6am`() {
        // 2026-04-27 is a Monday.
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.EVERY_DAY, clock)
        assertEquals(Duration.ofHours(20).toMillis(), delay)
    }

    @Test fun `daily mask, current is Mon 5am, target 6am — schedules today 6am`() {
        val clock = clockAt(2026, 4, 27, 5, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.EVERY_DAY, clock)
        assertEquals(Duration.ofHours(1).toMillis(), delay)
    }

    @Test fun `weekdays mask, current is Fri 10am — schedules Mon 6am`() {
        // 2026-05-01 is a Friday.
        val clock = clockAt(2026, 5, 1, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.WEEKDAYS, clock)
        // Sat 6am skipped, Sun 6am skipped, Mon 6am = 2 days + 20h = 68h
        assertEquals(Duration.ofHours(68).toMillis(), delay)
    }

    @Test fun `weekends mask, current is Mon 10am — schedules Sat 6am`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.WEEKENDS, clock)
        // Mon 10am to Sat 6am: 4 full days (Tue, Wed, Thu, Fri) + 20h = 116h
        assertEquals(Duration.ofHours(116).toMillis(), delay)
    }

    @Test fun `Sunday-only mask, current is Mon 10am — schedules next Sun 6am`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet(0b1000000), clock)
        // Mon 10am → next Sun 6am: 6 days minus 4 hours = 5d 20h = 140h
        assertEquals(Duration.ofHours(140).toMillis(), delay)
    }

    @Test fun `empty bitmask returns null`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        val delay = scheduler.computeDelayToNextSync(6, 0, DayOfWeekSet.NONE, clock)
        assertNull(delay)
    }

    @Test fun `all delays are positive`() {
        val clock = clockAt(2026, 4, 27, 10, 0)
        listOf(
            DayOfWeekSet.EVERY_DAY,
            DayOfWeekSet.WEEKDAYS,
            DayOfWeekSet.WEEKENDS,
            DayOfWeekSet(0b1000000),
        ).forEach { mask ->
            val delay = scheduler.computeDelayToNextSync(6, 0, mask, clock)!!
            assertTrue("delay for $mask must be positive, got $delay", delay > 0)
        }
    }
}
