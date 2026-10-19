package com.stash.feature.listening.recap

import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningRange
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The period a Recap story tells: one calendar month or one calendar year (owner decision
 * 2026-10-18: monthly recaps + the year). A period still in progress is "so far".
 */
sealed class RecapPeriod {
    abstract val year: Int

    /** [month] 1..12. */
    data class MonthOf(override val year: Int, val month: Int) : RecapPeriod() {
        init {
            require(month in 1..12) { "month $month" }
        }
    }

    data class YearOf(override val year: Int) : RecapPeriod()

    /** The route's kind argument. */
    val kind: String get() = if (this is YearOf) KIND_YEAR else KIND_MONTH

    /** The route's month argument (0 for a year). */
    val monthArg: Int get() = (this as? MonthOf)?.month ?: 0

    /** The period's days, cut at [today] while it's in progress. */
    fun dayRange(today: Long): DayRange = when (this) {
        is YearOf -> ListeningRange.year(year, today)
        is MonthOf -> {
            val ym = YearMonth.of(year, month)
            val from = ym.atDay(1).toEpochDay()
            val to = minOf(ym.atEndOfMonth().toEpochDay(), today)
            DayRange(from, maxOf(from, to))
        }
    }

    /** Every day of the period, finished or not (the calendar dots' grid). */
    fun fullRange(): DayRange = when (this) {
        is YearOf -> DayRange(LocalDate.of(year, 1, 1).toEpochDay(), LocalDate.of(year, 12, 31).toEpochDay())
        is MonthOf -> YearMonth.of(year, month).let { DayRange(it.atDay(1).toEpochDay(), it.atEndOfMonth().toEpochDay()) }
    }

    /** True while [today] is inside the period (the recap is "so far"). */
    fun inProgress(today: Long): Boolean = today in fullRange()

    /** "October" or "2026". */
    fun name(locale: Locale = Locale.getDefault()): String = when (this) {
        is YearOf -> "$year"
        is MonthOf -> Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale)
    }

    /** "October 2026" or "2026": the share images' footer and the playlist's name. */
    fun longName(locale: Locale = Locale.getDefault()): String = when (this) {
        is YearOf -> "$year"
        is MonthOf -> "${name(locale)} $year"
    }

    /**
     * The story's title: "Your October", "Your October so far", "Your 2026". The year in December
     * is "Your 2026" (its featured month); earlier it's "so far".
     */
    fun title(today: Long, locale: Locale = Locale.getDefault()): String {
        val soFar = inProgress(today) && !(this is YearOf && LocalDate.ofEpochDay(today).monthValue == 12)
        return "Your ${name(locale)}" + if (soFar) " so far" else ""
    }

    companion object {
        const val KIND_MONTH = "month"
        const val KIND_YEAR = "year"

        /** The period a route names, or null when the arguments don't make one. */
        fun fromRoute(kind: String?, year: Int, month: Int): RecapPeriod? = when {
            year !in 1970..9999 -> null
            kind == KIND_YEAR -> YearOf(year)
            kind == KIND_MONTH && month in 1..12 -> MonthOf(year, month)
            else -> null
        }
    }
}

/** One recap on Your listening's "Past recaps" row, with its listens. */
data class PastRecap(val period: RecapPeriod, val plays: Int)

/**
 * Which recap Home's door and Your listening's banner offer (PHASE3-STORY-SPEC "Which recaps
 * exist"). Pure: the caller counts listens per candidate and takes the first that qualifies.
 */
object RecapAvailability {

    /** A period needs this many listens for a recap (fewer makes a thin, sad story). */
    const val MIN_PLAYS = 20

    /** The year takes the door from December 1 to January 15 (the year just ended, in January). */
    const val YEAR_FEATURE_LAST_JANUARY_DAY = 15

    /** Last month stays on the door for the first week of a new month. */
    const val LAST_MONTH_DAYS = 7

    /**
     * Home's door, best first: December 1 – January 15 the year; then, in a month's first week,
     * last month; then this month so far.
     */
    fun doorCandidates(today: LocalDate): List<RecapPeriod> = buildList {
        featuredYear(today)?.let { add(RecapPeriod.YearOf(it)) }
        addAll(monthCandidates(today))
    }

    /**
     * Your listening's banner for the range on screen: the year for 12 months and All time (the
     * featured year in its window, else this year so far), else the door's month.
     */
    fun bannerCandidates(today: LocalDate, range: ListeningRange): List<RecapPeriod> =
        if (range == ListeningRange.Months12 || range == ListeningRange.AllTime) {
            listOf(RecapPeriod.YearOf(featuredYear(today) ?: today.year))
        } else {
            monthCandidates(today)
        }

    /** The first candidate with enough listens ([playsIn] counts a candidate's), or null. */
    suspend fun firstQualifying(candidates: List<RecapPeriod>, playsIn: suspend (RecapPeriod) -> Int): RecapPeriod? =
        candidates.firstOrNull { playsIn(it) >= MIN_PLAYS }

    /**
     * Every recap the history holds, for Your listening's "Past recaps": each month with
     * [MIN_PLAYS] listens (this month too, "so far"), newest first, then each FINISHED year with
     * as many. [playsPerDay] is (epoch-day, listens) for the whole history.
     */
    fun pastRecaps(playsPerDay: List<Pair<Long, Int>>, today: LocalDate): List<PastRecap> {
        val byMonth = HashMap<RecapPeriod.MonthOf, Int>()
        val byYear = HashMap<Int, Int>()
        for ((day, plays) in playsPerDay) {
            val d = LocalDate.ofEpochDay(day)
            byMonth.merge(RecapPeriod.MonthOf(d.year, d.monthValue), plays, Int::plus)
            byYear.merge(d.year, plays, Int::plus)
        }
        val months = byMonth.filter { it.value >= MIN_PLAYS }
            .entries.sortedWith(compareByDescending<Map.Entry<RecapPeriod.MonthOf, Int>> { it.key.year }.thenByDescending { it.key.month })
            .map { PastRecap(it.key, it.value) }
        val years = byYear.filter { it.key < today.year && it.value >= MIN_PLAYS }
            .entries.sortedByDescending { it.key }
            .map { PastRecap(RecapPeriod.YearOf(it.key), it.value) }
        return months + years
    }

    /** The year featured on [today], or null outside December 1 – January 15. */
    fun featuredYear(today: LocalDate): Int? = when {
        today.monthValue == 12 -> today.year
        today.monthValue == 1 && today.dayOfMonth <= YEAR_FEATURE_LAST_JANUARY_DAY -> today.year - 1
        else -> null
    }

    private fun monthCandidates(today: LocalDate): List<RecapPeriod> {
        val current = RecapPeriod.MonthOf(today.year, today.monthValue)
        if (today.dayOfMonth > LAST_MONTH_DAYS) return listOf(current)
        val last = today.minusMonths(1)
        return listOf(RecapPeriod.MonthOf(last.year, last.monthValue), current)
    }
}
