package com.stash.feature.listening.ui

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * A count short enough for a big-number tile: written out in full below [COMPACT_FROM] ("9,876"),
 * then in thousands, millions or billions to [SIGNIFICANT] significant digits ("12.3K", "330K",
 * "1.23M"), so a tile's number is never more than five characters and never has to be cut.
 *
 * Not the artist page's listener-count rule (#770, `shortMonthlyListeners`), which compacts from
 * 1,000 and keeps whole units ("1K", "12K", "574K"): a stats tile has room for a count in full to
 * 9,999, and three significant digits after it ("12.3K") say more than "12K". This one is local and
 * pure (#770's lives in feature:search and needs Android's ICU, which a JVM test can't load).
 * TODO: one shared count formatter once #770 is merged, with both rules as its options.
 * The unit letters are English, as everything in this module is today; the digits, the grouping and
 * the decimal separator follow [Locale].
 */
internal object CompactCount {

    /** The first count that is compacted: 9,999 still fits a tile in full. */
    const val COMPACT_FROM = 10_000L

    /** Digits kept once compacted: "12.3K", "123K", "1.23M". */
    private const val SIGNIFICANT = 3

    /** Each unit's size and letter, smallest first. */
    private val UNITS = listOf(
        1_000L to "K",
        1_000_000L to "M",
        1_000_000_000L to "B",
        1_000_000_000_000L to "T",
    )

    private val THOUSAND = BigDecimal.valueOf(1_000L)

    /** [count] as a tile shows it ("12.3K"); see [CompactCount]. */
    fun format(count: Long, locale: Locale = Locale.getDefault()): String {
        if (count < COMPACT_FROM) return full(count, locale)
        var unit = UNITS.indexOfLast { count >= it.first }
        var value = scaled(count, unit)
        // 999,950 rounds to "1000K": that is the next unit's "1M".
        if (value >= THOUSAND && unit < UNITS.lastIndex) {
            unit++
            value = scaled(count, unit)
        }
        val digits = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = SIGNIFICANT - 1
            isGroupingUsed = false
        }
        return digits.format(value.stripTrailingZeros()) + UNITS[unit].second
    }

    /** [count] in full with the locale's grouping ("12,345"): what TalkBack reads for a compacted tile. */
    fun full(count: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(count)

    /** [count] in [unit]s, rounded half-to-even to [SIGNIFICANT] digits (as ICU rounds). */
    private fun scaled(count: Long, unit: Int): BigDecimal =
        BigDecimal.valueOf(count)
            .divide(BigDecimal.valueOf(UNITS[unit].first))
            .round(MathContext(SIGNIFICANT, RoundingMode.HALF_EVEN))
}
