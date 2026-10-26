package com.stash.feature.record.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [JsDate] against V8 itself: every string below went through node's `Date.parse` (node 24, TZ=UTC), and these are its answers
 * (null: NaN). The forms V8 reads with its looser fallback ("2021-1-1") are NaN here and listed apart.
 */
class JsDateTest {
    private val v8 = listOf(
        "2021-01-01T08:23:45Z" to 1609489425000L,
        "2021-01-01T08:23:45.123Z" to 1609489425123L,
        "2021-01-01T08:23:45.123456Z" to 1609489425123L,
        "2021-01-01T08:23Z" to 1609489380000L,
        "2021-01-01T08:23:45" to 1609489425000L,
        "2021-01-01" to 1609459200000L,
        "2021-01" to 1609459200000L,
        "2021" to 1609459200000L,
        "2021-02-30T00:00:00Z" to 1614643200000L,
        "2021-13-01T00:00:00Z" to null,
        "2021-01-01T24:00:00Z" to 1609545600000L,
        "2021-01-01T25:00:00Z" to null,
        "2021-01-01t08:23:45z" to 1609489425000L,
        "2021-01-01 08:23:45Z" to 1609489425000L,
        "2021-01-01T08:23:45+02:00" to 1609482225000L,
        "2021-01-01T08:23:45+0200" to 1609482225000L,
        "+002021-01-01T00:00:00Z" to 1609459200000L,
        " 2021-01-01T08:23:45Z" to null,
        "2021-01-01T08:23:45Z " to null,
        "not a time" to null,
        "2021-01-01T08:23:45.1Z" to 1609489425100L,
        "2021-01-01T08:23:60Z" to null,
        "2021-02-29T00:00:00Z" to 1614556800000L,
        "2021-04-31" to 1619827200000L,
        "2021-01-01T08:23:45.Z" to null,
        "2021-01-01T08:23:45.123+02:00" to 1609482225123L,
        "2021-01-01T24:00:01Z" to null,
        "2021-01-01T24:01Z" to null,
        "2021-01-01T24:00Z" to 1609545600000L,
        "2021-00-01" to null,
        "2021-01-00" to null,
        "2021-01-32" to null,
        "2021-02-31T00:00Z" to 1614729600000L,
        "2021-01-01T08Z" to null,
        "2021-01-01T8:23Z" to null,
        "20210101" to null,
        "2021-01-01T08:23:45+24:00" to null,
        "2021-01-01T08:23:45+23:59" to 1609403085000L,
        "2021-01-01T08:23:45+2400" to null,
        "-000001-01-01T00:00:00Z" to -62198755200000L,
        "2021-01-01T08:23:45.123456789012Z" to 1609489425123L,
        "2021-01-01 08:23:45" to 1609489425000L,
        "2021-01-01T08:23:45 Z" to null,
        "2021-01-01Z" to 1609459200000L,
        "2021-01-01T08:23:45+02" to null,
        "2021-01T08:23Z" to 1609489380000L,
        "2021T08:23Z" to 1609489380000L,
        "2021-01-01T08:23:45.1234" to 1609489425123L,
        "2021-01-01T08:23:45,123Z" to null,
        "2021-01-01T08:60Z" to null,
        "2021-01-01T08:23:45-00:00" to 1609489425000L,
        "2021-01-01T08:23:45+00:60" to null,
        "2021-1-01T00:00Z" to null,
        "2021-01-01T08:23:45ZZ" to null,
        "2021-01-01T" to null,
        "2021-01-01TZ" to null,
        "0099-01-01T00:00Z" to -59042995200000L,
        "2021-01-01T00:00:00.0005Z" to 1609459200000L,
        "+275760-09-13T00:00:00Z" to 8640000000000000L,
        "+275760-09-13T00:00:00.001Z" to null,
        "-271821-04-20T00:00:00Z" to -8640000000000000L,
        "-271821-04-19T23:59:59.999Z" to null,
        "-000000-01-01T00:00:00Z" to null,
        "2021-01-01T24:00:00.000Z" to 1609545600000L,
        "2021-01-01T24:00:00.001Z" to null,
    )

    @Test fun `Date dot parse reads the date-time format exactly as V8 does`() {
        for ((s, want) in v8) assertEquals(s, want?.toDouble() ?: Double.NaN, JsDate.parse(s, ZoneOffset.UTC), 0.0)
    }

    @Test fun `a date and time with no offset is local time, a date alone UTC`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(1_609_489_425_000.0 - 9 * 3_600_000, JsDate.parse("2021-01-01T08:23:45", tokyo), 0.0)
        assertEquals(1_609_459_200_000.0, JsDate.parse("2021-01-01", tokyo), 0.0)
        assertEquals(1_609_489_425_000.0, JsDate.parse("2021-01-01T08:23:45Z", tokyo), 0.0)
    }

    @Test fun `V8's looser fallback forms are no time here`() {
        // V8 reads these as local midnight; Spotify never writes them
        assertEquals(Double.NaN, JsDate.parse("2021-1-1", ZoneOffset.UTC), 0.0)
    }

    @Test fun `Date dot UTC runs months, days, hours and minutes on, and reads years 0 to 99 as 1900 to 1999`() {
        val v8 = listOf(
            listOf(2024, 12, 1, 10, 0, 0) to 1_735_725_600_000L,
            listOf(2024, 1, 30, 10, 0, 0) to 1_709_287_200_000L,
            listOf(2024, 10, 32, 10, 0, 0) to 1_733_133_600_000L,
            listOf(2024, 10, 5, 25, 0, 0) to 1_730_854_800_000L,
            listOf(2024, 10, 5, 10, 99, 0) to 1_730_806_740_000L,
            listOf(99, 0, 1, 0, 0, 0) to 915_148_800_000L,
            listOf(0, 0, 1, 0, 0, 0) to -2_208_988_800_000L,
            listOf(2024, -1, 1, 0, 0, 0) to 1_701_388_800_000L,
            listOf(2024, 10, 0, 0, 0, 0) to 1_730_332_800_000L,
        )
        for ((a, want) in v8) assertEquals(a.toString(), want.toDouble(), JsDate.utc(a[0], a[1], a[2], a[3], a[4], a[5]), 0.0)
    }
}
