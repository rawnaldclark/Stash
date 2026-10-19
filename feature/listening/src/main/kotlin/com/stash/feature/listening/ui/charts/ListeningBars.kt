package com.stash.feature.listening.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A row of rounded vertical bars, one per value, bottom-aligned, scaled to the largest value.
 * Zero values draw a short stub so the time axis reads as continuous. [highlight] (an index)
 * draws in the full accent and the rest a step softer; [onBarClick] gets the tapped index (only
 * for bars with a value). [summary] is what TalkBack reads for the whole chart.
 *
 * Drawn on one Canvas (no per-bar layout), so a 31-bar month or a 53-bar year costs the same.
 */
@Composable
internal fun ListeningBars(
    values: List<Long>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
    highlight: Int? = null,
    barColor: Color = MaterialTheme.colorScheme.primary,
    emptyColor: Color = StashTheme.extendedColors.glassBorderBright,
    /** Space between bars as a share of each bar's slot. */
    gapFraction: Float = 0.28f,
    onBarClick: ((Int) -> Unit)? = null,
) {
    val currentValues by rememberUpdatedState(values)
    val click by rememberUpdatedState(onBarClick)
    val tap = if (onBarClick != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val n = currentValues.size
                if (n == 0) return@detectTapGestures
                val index = (offset.x / (size.width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                if (currentValues[index] > 0) click?.invoke(index)
            }
        }
    } else {
        Modifier
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(if (summary.isNotEmpty()) Modifier.semantics { contentDescription = summary } else Modifier)
            .then(tap),
    ) {
        val n = values.size
        if (n == 0) return@Canvas
        val max = values.max().coerceAtLeast(1L)
        val slot = size.width / n
        val barWidth = (slot * (1f - gapFraction)).coerceAtLeast(1f)
        val radius = (barWidth / 2f).coerceAtMost(6.dp.toPx())
        val stub = 3.dp.toPx()
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barWidth) / 2f
            if (v <= 0) {
                drawRoundRect(
                    color = emptyColor,
                    topLeft = Offset(x, size.height - stub),
                    size = Size(barWidth, stub),
                    cornerRadius = CornerRadius(stub / 2f),
                )
            } else {
                val h = (size.height * v / max).coerceAtLeast(stub)
                val color = if (highlight == null || highlight == i) barColor else barColor.copy(alpha = 0.55f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(radius),
                )
            }
        }
    }
}
