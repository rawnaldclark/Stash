package com.stash.feature.listening.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One day of Home's week chart: its listening, the initial under its bar ("M") and whether it is
 * today (drawn in the full colour). TalkBack reads the week card as one sentence, not per day.
 */
internal data class WeekBarDay(
    /** The day (epoch day, local). */
    val day: Long,
    /** Listening time that day; 0 draws the empty-day stub. */
    val listenedMs: Long,
    val initial: String,
    val isToday: Boolean,
)

/**
 * The week chart's days from the week's bars ([bars]: listening per day, oldest first, today last,
 * the order [com.stash.feature.listening.WeekSummary.bars] keeps) and [today]: each day's narrow
 * name in [locale] ("M T W T F S S", so the axis reads as days and not as "6 days ago"), today
 * marked. A negative time (never expected) draws as nothing.
 */
internal fun weekBarDays(bars: List<Long>, today: Long, locale: Locale = Locale.getDefault()): List<WeekBarDay> =
    bars.mapIndexed { i, ms ->
        val day = today - (bars.lastIndex - i)
        val weekday = LocalDate.ofEpochDay(day).dayOfWeek
        val listened = ms.coerceAtLeast(0L)
        WeekBarDay(
            day = day,
            listenedMs = listened,
            initial = weekday.getDisplayName(TextStyle.NARROW, locale),
            isToday = i == bars.lastIndex,
        )
    }

/**
 * The week bars' colour: the theme's [neutral] ink with nothing playing ([tint] 0), the album's
 * [accent] while a song plays ([tint] 1, `AlbumChrome.tint`), blended in between while it fades.
 * Exactly [neutral] and exactly [accent] at the ends (no colour-space round trip there); a [tint]
 * outside 0..1 counts as the nearer end.
 */
internal fun weekBarColor(neutral: Color, accent: Color, tint: Float): Color = when {
    tint <= 0f -> neutral
    tint >= 1f -> accent
    else -> lerp(neutral, accent, tint)
}

/**
 * A listening time ("1,234 h 5 min", [com.stash.feature.listening.ListeningLabels.listenTime]'s one format) as its number and
 * unit pairs, [("1,234", "h"), ("5", "min")], so the number can be set big and its unit small,
 * the way Home's Your record card sets its hours. Each pair is kept together when the line wraps
 * (a non-breaking space between them), so a large font breaks "1,234 h / 5 min", never "1,234 / h".
 * Text without a number comes back whole as one unit with an empty number.
 */
internal fun listenTimeParts(time: String): List<Pair<String, String>> {
    val parts = TIME_PART.findAll(time).map { it.groupValues[1] to it.groupValues[2] }.toList()
    return parts.ifEmpty { listOf("" to time.trim()) }
}

/** A number (digits with grouping commas) and the word after it. */
private val TIME_PART = Regex("""(\d[\d,]*)\s*([^\d\s]+)""")
