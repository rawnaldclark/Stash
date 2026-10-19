package com.stash.core.data.lastfm.recap

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * A calendar month or year of Last.fm history, in the phone's time zone (the zone Stash's own
 * listening days use). Months are the unit of storage: a year is read as its months.
 */
sealed interface LastFmRecapSpan {
    /** The cache key: "2026-10" or "2026". */
    val key: String

    data class Month(val month: YearMonth) : LastFmRecapSpan {
        override val key: String get() = "%04d-%02d".format(month.year, month.monthValue)
    }

    data class Year(val year: Int) : LastFmRecapSpan {
        override val key: String get() = "%04d".format(year)
    }

    /** Start (inclusive) and end (exclusive) of the span, epoch seconds, in [zone]. */
    fun bounds(zone: ZoneId): LongRange {
        val (from, until) = when (this) {
            is Month -> month.atDay(1) to month.plusMonths(1).atDay(1)
            is Year -> YearMonth.of(year, 1).atDay(1) to YearMonth.of(year + 1, 1).atDay(1)
        }
        return from.atStartOfDay(zone).toEpochSecond() until until.atStartOfDay(zone).toEpochSecond()
    }

    /** The months a year is read as, January to December (a month is just itself). */
    fun months(): List<YearMonth> = when (this) {
        is Month -> listOf(month)
        is Year -> (1..12).map { YearMonth.of(year, it) }
    }
}

/**
 * When cached Last.fm counts and scrobbles may be trusted, pure so the rules are tested.
 *
 * Last.fm accepts scrobbles up to two weeks late, so a period is only *settled* [SETTLE_DAYS] after
 * it ends. Anything fetched after that never changes and is kept for good; anything fetched
 * earlier is good for a short time only ([ttlMs]).
 */
object LastFmRecapFreshness {

    /** Last.fm takes scrobbles this many days late; a period is final this long after it ends. */
    const val SETTLE_DAYS = 14L

    private const val DAY_MS = 86_400_000L

    /** Settled = [nowMs] is at least [SETTLE_DAYS] past the end of a span ending at [endExclusiveSec]. */
    fun isSettled(endExclusiveSec: Long, nowMs: Long): Boolean = nowMs >= endExclusiveSec * 1000 + SETTLE_DAYS * DAY_MS

    /**
     * A cached value fetched at [fetchedAtMs] for a span ending at [endExclusiveSec] is still
     * good at [nowMs]: fetched once the span had settled (good for ever), or less than [ttlMs] ago.
     */
    fun isFresh(fetchedAtMs: Long, endExclusiveSec: Long, nowMs: Long, ttlMs: Long): Boolean =
        isSettled(endExclusiveSec, fetchedAtMs) || (nowMs - fetchedAtMs in 0 until ttlMs)

    /** Seconds of [nowMs], for clamping a request's `to` at now. */
    fun nowSec(nowMs: Long): Long = Instant.ofEpochMilli(nowMs).epochSecond
}
