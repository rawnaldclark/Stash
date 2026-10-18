package com.stash.core.data.listening

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [ListeningDay.localStamp] is the one place a listen's day and minute are worked out, so its edge
 * cases are pinned here: DST in both directions, half- and quarter-hour zones, midnight, travel.
 * Every instant is a literal UTC time so the expectations can be checked by hand.
 */
class ListeningDayTest {

    private val newYork = ZoneId.of("America/New_York")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val chatham = ZoneId.of("Pacific/Chatham")

    private fun ms(utc: String) = Instant.parse(utc).toEpochMilli()
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    @Test fun `utc epoch is day 0 minute 0`() {
        assertEquals(LocalStamp(0, 0), ListeningDay.localStamp(0L, ZoneOffset.UTC))
        assertEquals(LocalStamp(-1, 19 * 60), ListeningDay.localStamp(0L, newYork))
    }

    @Test fun `spring forward 2026-03-08 in New York skips 2 to 3 AM`() {
        // 06:59 UTC = 01:59 EST; one minute later = 03:00 EDT.
        assertEquals(LocalStamp(day(2026, 3, 8), 1 * 60 + 59), ListeningDay.localStamp(ms("2026-03-08T06:59:00Z"), newYork))
        assertEquals(LocalStamp(day(2026, 3, 8), 3 * 60), ListeningDay.localStamp(ms("2026-03-08T07:00:00Z"), newYork))
        // Midnight before the change is still EST (UTC-5).
        assertEquals(LocalStamp(day(2026, 3, 7), 1439), ListeningDay.localStamp(ms("2026-03-08T04:59:59Z"), newYork))
        assertEquals(LocalStamp(day(2026, 3, 8), 0), ListeningDay.localStamp(ms("2026-03-08T05:00:00Z"), newYork))
        // The next midnight is EDT (UTC-4): the 23-hour day still ends at minute 1439.
        assertEquals(LocalStamp(day(2026, 3, 8), 1439), ListeningDay.localStamp(ms("2026-03-09T03:59:00Z"), newYork))
        assertEquals(LocalStamp(day(2026, 3, 9), 0), ListeningDay.localStamp(ms("2026-03-09T04:00:00Z"), newYork))
    }

    @Test fun `fall back 2026-11-01 in New York reads 1-30 AM twice on the same day`() {
        assertEquals(LocalStamp(day(2026, 11, 1), 90), ListeningDay.localStamp(ms("2026-11-01T05:30:00Z"), newYork)) // EDT
        assertEquals(LocalStamp(day(2026, 11, 1), 90), ListeningDay.localStamp(ms("2026-11-01T06:30:00Z"), newYork)) // EST
        // The 25-hour day: midnight was EDT (UTC-4), the next midnight is EST (UTC-5).
        assertEquals(LocalStamp(day(2026, 11, 1), 0), ListeningDay.localStamp(ms("2026-11-01T04:00:00Z"), newYork))
        assertEquals(LocalStamp(day(2026, 11, 1), 1439), ListeningDay.localStamp(ms("2026-11-02T04:59:00Z"), newYork))
        assertEquals(LocalStamp(day(2026, 11, 2), 0), ListeningDay.localStamp(ms("2026-11-02T05:00:00Z"), newYork))
    }

    @Test fun `Asia Kolkata's half-hour offset moves midnight to 18-30 UTC`() {
        assertEquals(LocalStamp(day(2026, 10, 17), 1439), ListeningDay.localStamp(ms("2026-10-17T18:29:59Z"), kolkata))
        assertEquals(LocalStamp(day(2026, 10, 18), 0), ListeningDay.localStamp(ms("2026-10-17T18:30:00Z"), kolkata))
        assertEquals(LocalStamp(day(2026, 10, 18), 5 * 60 + 30), ListeningDay.localStamp(ms("2026-10-18T00:00:00Z"), kolkata))
    }

    @Test fun `Pacific Chatham's quarter-hour offset, in and out of its summer time`() {
        // October: +13:45, so local midnight is 10:15 UTC the day before.
        assertEquals(LocalStamp(day(2026, 10, 17), 1439), ListeningDay.localStamp(ms("2026-10-17T10:14:00Z"), chatham))
        assertEquals(LocalStamp(day(2026, 10, 18), 0), ListeningDay.localStamp(ms("2026-10-17T10:15:00Z"), chatham))
        // June: +12:45.
        assertEquals(LocalStamp(day(2026, 6, 2), 0), ListeningDay.localStamp(ms("2026-06-01T11:15:00Z"), chatham))
    }

    @Test fun `the same instant is a different day in a different place`() {
        val instant = ms("2026-10-18T02:00:00Z")
        assertEquals(day(2026, 10, 17), ListeningDay.localStamp(instant, ZoneId.of("America/Los_Angeles")).day)
        assertEquals(day(2026, 10, 18), ListeningDay.localStamp(instant, ZoneId.of("Asia/Tokyo")).day)
    }

    @Test fun `today and dateOf agree with the stamp`() {
        val now = ms("2026-10-18T12:00:00Z")
        assertEquals(day(2026, 10, 18), ListeningDay.today(now, ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 10, 18), ListeningDay.dateOf(day(2026, 10, 18)))
    }
}
