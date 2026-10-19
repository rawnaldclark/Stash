package com.stash.feature.listening.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The listening clock: 24 radial bars, one per hour, midnight at the top and noon at the bottom,
 * each as long as that hour's share of the busiest hour. The peak hour is in the full accent, the
 * rest softer; a faint ring marks the dial. [hourLabels] are the four quarter labels (12 AM, 6 AM,
 * 12 PM, 6 PM) drawn outside the bars; [centerTop] / [centerValue] sit in the middle ("Peak" /
 * "9 PM"). TalkBack reads [summary] for the whole clock.
 */
@Composable
internal fun ListeningClock(
    hourly: List<Int>,
    peakHour: Int?,
    hourLabels: List<String>,
    centerTop: String,
    centerValue: String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val ring = StashTheme.extendedColors.glassBorderBright
    val labelColor = StashTheme.extendedColors.textTertiary
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { contentDescription = summary },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension / 2f
            // Room for the quarter labels, measured (they grow with the font size): the side labels
            // need their width, the top and bottom ones their height.
            val layouts = hourLabels.take(4).map { measurer.measure(it, labelStyle) }
            val sideWidth = layouts.filterIndexed { i, _ -> i % 2 == 1 }.maxOfOrNull { it.size.width } ?: 0
            val capHeight = layouts.filterIndexed { i, _ -> i % 2 == 0 }.maxOfOrNull { it.size.height } ?: 0
            val labelBand = maxOf(sideWidth, capHeight).toFloat() + 8.dp.toPx()
            val maxBar = outer - labelBand
            val inner = maxBar * 0.46f
            val max = (hourly.maxOrNull() ?: 0).coerceAtLeast(1)
            val stroke = (2f * PI.toFloat() * inner / 24f) * 0.52f

            drawCircle(color = ring, radius = inner - stroke, center = center, style = Stroke(width = 1.dp.toPx()))

            for (h in 0 until 24) {
                val angle = (h / 24.0) * 2 * PI - PI / 2 // midnight at the top, clockwise
                val v = hourly.getOrElse(h) { 0 }
                val len = if (v > 0) (maxBar - inner) * v / max else 0f
                val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val start = center + dir * inner
                val end = center + dir * (inner + len.coerceAtLeast(0.5f))
                val color = when {
                    v <= 0 -> ring
                    h == peakHour -> accent
                    else -> accent.copy(alpha = 0.5f)
                }
                drawLine(color = color, start = start, end = end, strokeWidth = stroke, cap = StrokeCap.Round)
            }

            // Quarter labels just outside the longest possible bar.
            layouts.forEachIndexed { i, layout ->
                val angle = (i / 4.0) * 2 * PI - PI / 2
                val r = maxBar + labelBand / 2f + 2.dp.toPx()
                val p = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * r
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(
                centerValue,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
