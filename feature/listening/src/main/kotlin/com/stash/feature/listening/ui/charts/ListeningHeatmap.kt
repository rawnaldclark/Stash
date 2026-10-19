package com.stash.feature.listening.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.data.listening.ListeningStatsCalculator.HEAT_LEVELS
import com.stash.core.data.listening.ListeningStatsCalculator.HeatCell
import com.stash.core.ui.theme.StashTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The day heatmap: one column per week (oldest left), seven squares per column from the first
 * day of the week, shaded by listens relative to the busiest day shown, with month names above
 * the column where each month starts. Wider than the screen (a year is 53 weeks), it scrolls and
 * opens on the newest week. A tap on a day with listens calls [onDayClick].
 */
@Composable
internal fun ListeningHeatmap(
    weeks: List<List<HeatCell?>>,
    summary: String,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The TalkBack action name for a day ("Open Sat, Oct 11 in History"). */
    dayActionLabel: ((Long) -> String)? = null,
    locale: Locale = Locale.getDefault(),
) {
    val accent = MaterialTheme.colorScheme.primary
    val empty = StashTheme.extendedColors.glassBorder
    val labelColor = StashTheme.extendedColors.textTertiary
    val shades = remember(accent, empty) { heatShades(accent, empty) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val currentWeeks by rememberUpdatedState(weeks)
    val click by rememberUpdatedState(onDayClick)
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("MMM", locale) }

    val labels = remember(weeks) { heatmapMonthLabels(weeks) }

    // TalkBack can't tap a square: the most recent days with listens are custom actions.
    val actionDays = remember(weeks) {
        weeks.asReversed().flatMap { w -> w.asReversed().filterNotNull().filter { it.plays > 0 } }.take(MAX_DAY_ACTIONS).map { it.day }
    }

    // Weeks run oldest to newest left to right in every language (and the scroll opens on the
    // newest week): kept left-to-right in a right-to-left layout too.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A short range fills the card with bigger squares; a long one keeps readable squares
            // and scrolls, opening on the newest week (reverseScrolling: position 0 = the right end).
            val natural = Cell + Gap
            val step: Dp = if (weeks.isNotEmpty() && natural * weeks.size < maxWidth) {
                minOf(maxWidth / weeks.size, MaxStep)
            } else {
                natural
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true)) {
                Canvas(
                    modifier = Modifier
                        .width(step * weeks.size)
                        .height(LabelBand + step * 7)
                        .semantics {
                            contentDescription = summary
                            if (dayActionLabel != null) {
                                customActions = actionDays.map { day ->
                                    CustomAccessibilityAction(dayActionLabel(day)) { click(day); true }
                                }
                            }
                        }
                        .pointerInput(step) {
                            detectTapGestures { p ->
                                val px = step.toPx()
                                val col = (p.x / px).toInt()
                                val row = ((p.y - LabelBand.toPx()) / px).toInt()
                                val cell = currentWeeks.getOrNull(col)?.getOrNull(row)
                                if (cell != null && cell.plays > 0) click(cell.day)
                            }
                        },
                ) {
                    val px = step.toPx()
                    val cell = px - Gap.toPx()
                    val top = LabelBand.toPx()
                    val r = CornerRadius((cell * 0.2f).coerceAtMost(4.dp.toPx()))
                    labels.forEach { (col, day) ->
                        val text = LocalDate.ofEpochDay(day).format(monthFormat)
                        drawText(measurer.measure(text, labelStyle), topLeft = Offset(col * px, 0f))
                    }
                    weeks.forEachIndexed { col, week ->
                        week.forEachIndexed { row, c ->
                            if (c == null) return@forEachIndexed
                            drawRoundRect(
                                color = shades[c.level.coerceIn(0, HEAT_LEVELS)],
                                topLeft = Offset(col * px, top + row * px),
                                size = Size(cell, cell),
                                cornerRadius = r,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                shades.forEach { shade ->
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(shade))
                }
            }
            Spacer(Modifier.width(6.dp))
            Text("More", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
    }
}

/**
 * Where the month names go, as (column, a day of that month): over every week that holds a
 * month's 1st, and over the first week when no month starts within its first three columns (so
 * the grid always says where it begins without two names colliding).
 */
internal fun heatmapMonthLabels(weeks: List<List<HeatCell?>>): List<Pair<Int, Long>> {
    val starts = weeks.mapIndexedNotNull { col, week ->
        week.firstOrNull { it != null && LocalDate.ofEpochDay(it.day).dayOfMonth == 1 }?.let { col to it.day }
    }
    val firstCol = weeks.indexOfFirst { w -> w.any { it != null } }
    if (firstCol < 0) return starts
    val lead = if (starts.none { it.first <= firstCol + 2 }) {
        listOf(firstCol to weeks[firstCol].first { it != null }!!.day)
    } else {
        emptyList()
    }
    return lead + starts
}

/** Level 0 (no listens) through [HEAT_LEVELS] (the busiest days). */
private fun heatShades(accent: Color, empty: Color): List<Color> =
    listOf(empty) + (1..HEAT_LEVELS).map { accent.copy(alpha = 0.25f + 0.75f * (it - 1) / (HEAT_LEVELS - 1)) }

private val Cell = 12.dp
private val MaxStep = 24.dp

/** Days offered to TalkBack as actions: the most recent month of listening days. */
private const val MAX_DAY_ACTIONS = 31
private val Gap = 3.dp
private val LabelBand = 18.dp
