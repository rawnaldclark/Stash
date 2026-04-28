package com.stash.core.data.sync

import java.time.DayOfWeek

/**
 * Set of days of the week, encoded as a 7-bit bitmask.
 *
 * Bit layout: bit 0 = Monday, bit 1 = Tuesday … bit 6 = Sunday. This matches
 * `java.time.DayOfWeek.value - 1` (where MONDAY.value == 1).
 *
 * Used to express which days the auto-sync should run. The default
 * 0b1111111 (127) means every day, matching the prior "always daily" behavior
 * of [SyncScheduler.scheduleDailySync] before this field existed.
 */
@JvmInline
value class DayOfWeekSet(val bitmask: Int) {

    /** True if [day] is in the set. */
    fun contains(day: DayOfWeek): Boolean =
        (bitmask shr (day.value - 1)) and 1 == 1

    /** Returns a new set with [day] toggled to [on]. */
    fun with(day: DayOfWeek, on: Boolean): DayOfWeekSet {
        val mask = 1 shl (day.value - 1)
        return DayOfWeekSet(if (on) bitmask or mask else bitmask and mask.inv())
    }

    /** True if no days are set. UI shows the "pick at least one day" hint when this is true. */
    val isEmpty: Boolean get() = bitmask == 0

    /** True when all 7 days are set. */
    val isDaily: Boolean get() = bitmask == 0b1111111

    /** True when exactly Monday-Friday is set (bits 0..4). */
    val isWeekdays: Boolean get() = bitmask == 0b0011111

    /** True when exactly Saturday-Sunday is set (bits 5..6). */
    val isWeekends: Boolean get() = bitmask == 0b1100000

    /** Number of days set. */
    val count: Int get() = Integer.bitCount(bitmask)

    /**
     * Human-readable label for the schedule sentence:
     *  - `"daily"` when all 7 set
     *  - `"weekdays"` when Mon-Fri only
     *  - `"weekends"` when Sat-Sun only
     *  - `"none"` when empty
     *  - otherwise compact 3-letter abbreviations joined by ` · ` (e.g. `"Mon · Wed · Fri"`)
     */
    fun presetLabel(): String = when {
        isDaily -> "daily"
        isWeekdays -> "weekdays"
        isWeekends -> "weekends"
        isEmpty -> "none"
        else -> compactLabel()
    }

    /**
     * Always renders as the 3-letter abbreviations regardless of whether the
     * bitmask matches a preset. Useful for the day-picker panel header.
     */
    fun compactLabel(): String = ABBREV_BY_DAY
        .filter { (day, _) -> contains(day) }
        .joinToString(" · ") { (_, abbrev) -> abbrev }

    companion object {
        val EVERY_DAY = DayOfWeekSet(0b1111111)
        val WEEKDAYS = DayOfWeekSet(0b0011111)
        val WEEKENDS = DayOfWeekSet(0b1100000)
        val NONE = DayOfWeekSet(0)

        /** Mon-first ordering for compact-label rendering. */
        private val ABBREV_BY_DAY: List<Pair<DayOfWeek, String>> = listOf(
            DayOfWeek.MONDAY to "Mon",
            DayOfWeek.TUESDAY to "Tue",
            DayOfWeek.WEDNESDAY to "Wed",
            DayOfWeek.THURSDAY to "Thu",
            DayOfWeek.FRIDAY to "Fri",
            DayOfWeek.SATURDAY to "Sat",
            DayOfWeek.SUNDAY to "Sun",
        )
    }
}
