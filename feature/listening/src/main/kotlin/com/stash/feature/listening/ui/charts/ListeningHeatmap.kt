package com.stash.feature.listening.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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

    Column(modifier.fillMaxWidth()) {
        // reverseScrolling: scroll position 0 is the right end, so the newest week shows first.
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true)) {
            val width = (Cell + Gap) * weeks.size
            Canvas(
                modifier = Modifier
                    .width(width)
                    .height(LabelBand + (Cell + Gap) * 7)
                    .semantics { contentDescription = summary }
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            val step = (Cell + Gap).toPx()
                            val col = (p.x / step).toInt()
                            val row = ((p.y - LabelBand.toPx()) / step).toInt()
                            val cell = currentWeeks.getOrNull(col)?.getOrNull(row)
                            if (cell != null && cell.plays > 0) click(cell.day)
                        }
                    },
            ) {
                val step = (Cell + Gap).toPx()
                val cell = Cell.toPx()
                val top = LabelBand.toPx()
                val r = CornerRadius(2.5.dp.toPx())
                var lastMonth = -1
                var lastLabelEnd = -1f
                weeks.forEachIndexed { col, week ->
                    val x = col * step
                    // A month name over the first week that holds its 1st (or the first column).
                    val firstDay = week.firstOrNull { it != null }?.day
                    if (firstDay != null) {
                        val month = LocalDate.ofEpochDay(firstDay).monthValue
                        val startsMonth = week.any { it != null && LocalDate.ofEpochDay(it.day).dayOfMonth == 1 }
                        if (month != lastMonth && (startsMonth || lastMonth == -1)) {
                            val labelDay = week.firstOrNull { it != null && LocalDate.ofEpochDay(it.day).dayOfMonth == 1 }?.day ?: firstDay
                            val text = LocalDate.ofEpochDay(labelDay).format(monthFormat)
                            val layout = measurer.measure(text, labelStyle)
                            if (x >= lastLabelEnd) {
                                drawText(layout, topLeft = Offset(x, 0f))
                                lastLabelEnd = x + layout.size.width + 4.dp.toPx()
                            }
                            lastMonth = LocalDate.ofEpochDay(labelDay).monthValue
                        }
                    }
                    week.forEachIndexed { row, c ->
                        if (c == null) return@forEachIndexed
                        drawRoundRect(
                            color = shades[c.level.coerceIn(0, HEAT_LEVELS)],
                            topLeft = Offset(x, top + row * step),
                            size = Size(cell, cell),
                            cornerRadius = r,
                        )
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

/** Level 0 (no listens) through [HEAT_LEVELS] (the busiest days). */
private fun heatShades(accent: Color, empty: Color): List<Color> =
    listOf(empty) + (1..HEAT_LEVELS).map { accent.copy(alpha = 0.25f + 0.75f * (it - 1) / (HEAT_LEVELS - 1)) }

private val Cell = 12.dp
private val Gap = 3.dp
private val LabelBand = 18.dp
