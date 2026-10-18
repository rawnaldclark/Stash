package com.stash.core.data.listening

import java.time.LocalDate

/** An inclusive range of epoch-days in the listener's zone ([ListeningDay]). */
data class DayRange(val fromDay: Long, val toDay: Long) {
    init {
        require(fromDay <= toDay) { "empty day range $fromDay..$toDay" }
    }

    /** Number of days in the range. */
    val length: Long get() = toDay - fromDay + 1

    operator fun contains(day: Long): Boolean = day in fromDay..toDay
}

/**
 * The periods Top songs and Stats show. Rolling, ending today (owner decision 10): "30 days" is
 * today and the 29 days before it, "3 months" is from the same date three months ago (+1 day) to
 * today, so a range never jumps at the start of a month.
 */
sealed class ListeningRange(
    /** Stable id for saved state / nav arguments (see [fromKey]). */
    val key: String,
) {
    data object Today : ListeningRange("today")
    data object Days7 : ListeningRange("7d")
    data object Days30 : ListeningRange("30d")
    data object Months3 : ListeningRange("3m")
    data object Months6 : ListeningRange("6m")
    data object Months12 : ListeningRange("12m")
    data object AllTime : ListeningRange("all")

    /** A range picked by hand; [fromDay]..[toDay] inclusive epoch-days. */
    data class Custom(val fromDay: Long, val toDay: Long) : ListeningRange("custom:$fromDay:$toDay") {
        init {
            require(fromDay <= toDay) { "empty custom range $fromDay..$toDay" }
        }
    }

    /**
     * The days this range covers when today is [today]. [firstListenDay] bounds [AllTime] (null =
     * no listens yet: today only). A rolling range may reach before the first listen; that's
     * fine, the empty days simply have no listens.
     */
    fun toDayRange(today: Long, firstListenDay: Long? = null): DayRange {
        val date = LocalDate.ofEpochDay(today)
        return when (this) {
            Today -> DayRange(today, today)
            Days7 -> DayRange(today - 6, today)
            Days30 -> DayRange(today - 29, today)
            Months3 -> DayRange(date.minusMonths(3).plusDays(1).toEpochDay(), today)
            Months6 -> DayRange(date.minusMonths(6).plusDays(1).toEpochDay(), today)
            Months12 -> DayRange(date.minusMonths(12).plusDays(1).toEpochDay(), today)
            AllTime -> DayRange(minOf(firstListenDay ?: today, today), today)
            is Custom -> DayRange(fromDay, toDay)
        }
    }

    /**
     * The period just before this one, of the same length, for "vs previous period" deltas. Null
     * for [AllTime], which has nothing before it.
     */
    fun previous(today: Long, firstListenDay: Long? = null): DayRange? {
        if (this == AllTime) return null
        val current = toDayRange(today, firstListenDay)
        return DayRange(current.fromDay - current.length, current.fromDay - 1)
    }

    /** English label for logs and tests; the UI phases supply translated strings by [key]. */
    val label: String
        get() = when (this) {
            Today -> "Today"
            Days7 -> "7 days"
            Days30 -> "30 days"
            Months3 -> "3 months"
            Months6 -> "6 months"
            Months12 -> "12 months"
            AllTime -> "All time"
            is Custom -> "Custom"
        }

    companion object {
        /**
         * The fixed ranges in chip order (Custom is picked separately). Lazy: the subclasses'
         * instances don't exist yet while this companion initializes with the sealed class, so an
         * eager list would hold nulls.
         */
        val PRESETS: List<ListeningRange> by lazy { listOf(Today, Days7, Days30, Months3, Months6, Months12, AllTime) }

        /** The range saved as [key], or null when the key is unknown or malformed. */
        fun fromKey(key: String?): ListeningRange? {
            if (key == null) return null
            PRESETS.firstOrNull { it.key == key }?.let { return it }
            val parts = key.split(':')
            if (parts.size != 3 || parts[0] != "custom") return null
            val from = parts[1].toLongOrNull() ?: return null
            val to = parts[2].toLongOrNull() ?: return null
            return if (from <= to) Custom(from, to) else null
        }

        /** The calendar year [year] as days, cut at [today] for the year in progress ("so far"). */
        fun year(year: Int, today: Long): DayRange {
            val from = LocalDate.of(year, 1, 1).toEpochDay()
            val to = minOf(LocalDate.of(year, 12, 31).toEpochDay(), today)
            return DayRange(from, maxOf(from, to))
        }
    }
}
