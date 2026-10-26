package com.stash.feature.record.importer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * JavaScript's dates where the web's importer reads a time from the export (export.ts): `Date.parse` of Extended history's `ts`,
 * and `Date.UTC` for Account data's `endTime`. A time read differently would give the play another start, and so another id.
 */
internal object JsDate {
    /**
     * `Date.parse` (V8) of the date-time format: `YYYY`, `YYYY-MM` or `YYYY-MM-DD` (or a six-digit signed year), then optionally
     * `T`, `t` or a space and `HH:mm`, `:ss` and a fraction of any length (cut to milliseconds), then optionally `Z`, `z`, `±HH:mm`
     * or `±HHmm`. A day up to 31 runs on into the next month (February 30 is March 2, as V8 has it); hour 24 is allowed only as
     * 24:00 sharp. A date alone is UTC; a date and time with no offset is [zone]'s local time, as JavaScript reads it. Anything
     * else is NaN (V8's own fallback reads a few looser forms; Spotify writes none of them).
     */
    fun parse(s: String, zone: ZoneId = ZoneId.systemDefault()): Double {
        val m = ISO.matchEntire(s) ?: return Double.NaN
        val g = m.groupValues
        val year = g[1].toLong()
        if (g[1] == "-000000") return Double.NaN // ES: minus zero is no year
        val month = g[2].ifEmpty { "1" }.toInt()
        val day = g[3].ifEmpty { "1" }.toInt()
        if (month !in 1..12 || day !in 1..31) return Double.NaN
        val hasTime = g[4].isNotEmpty()
        val hour = g[4].ifEmpty { "0" }.toInt()
        val minute = g[5].ifEmpty { "0" }.toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        val millis = g[7].take(3).padEnd(3, '0').let { if (g[7].isEmpty()) 0 else it.toInt() }
        if (hour > 24 || minute > 59 || second > 59) return Double.NaN
        if (hour == 24 && (minute != 0 || second != 0 || g[7].any { it != '0' })) return Double.NaN
        val offsetMs: Long? = when {
            g[8].isNotEmpty() -> 0L
            g[9].isNotEmpty() -> {
                val oh = g[10].toInt()
                val om = g[11].toInt()
                if (oh > 23 || om > 59) return Double.NaN
                (if (g[9] == "-") -1 else 1) * (oh * 3_600_000L + om * 60_000L)
            }
            else -> null
        }
        // The day as V8 counts it: the month's first day, plus the day of the month less one
        val epochDay = runCatching { LocalDate.of(year.toInt(), month, 1).toEpochDay() + day - 1 }.getOrElse { return Double.NaN }
        val local = epochDay * DAY_MS + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
        val utc = when {
            offsetMs != null -> local - offsetMs
            !hasTime -> local
            else -> {
                // A local time: the offset the zone has at that wall-clock time
                val wall = LocalDateTime.of(LocalDate.ofEpochDay(Math.floorDiv(local, DAY_MS)), java.time.LocalTime.ofNanoOfDay(Math.floorMod(local, DAY_MS) * 1_000_000L))
                local - zone.rules.getOffset(wall).totalSeconds * 1_000L
            }
        }
        return if (kotlin.math.abs(utc.toDouble()) > MAX_TIME) Double.NaN else utc.toDouble()
    }

    /**
     * `Date.UTC(year, month, day, hour, minute, second)` for whole numbers: the month runs on into the next year (13 is the next
     * January), the day, hour, minute and second into the next month, day, hour and minute, as ECMAScript's MakeDay and MakeTime
     * have it. A year from 0 to 99 is 1900 to 1999.
     */
    fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Double {
        val y = if (year in 0..99) 1900 + year else year
        val ym = y + Math.floorDiv(month, 12)
        val mn = Math.floorMod(month, 12)
        val epochDay = runCatching { LocalDate.of(ym, mn + 1, 1).toEpochDay() }.getOrElse { return Double.NaN } + day - 1
        val t = epochDay * DAY_MS + hour * 3_600_000L + minute * 60_000L + second * 1_000L
        return if (kotlin.math.abs(t.toDouble()) > MAX_TIME) Double.NaN else t.toDouble()
    }

    private const val DAY_MS = 86_400_000L

    /** ECMAScript's TimeClip: ±100,000,000 days. */
    private const val MAX_TIME = 8.64e15

    private val ISO = Regex(
        "([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?" +
            "(?:[Tt ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?)?" +
            "(?:([Zz])|([+-])(\\d{2}):?(\\d{2}))?",
    )
}
