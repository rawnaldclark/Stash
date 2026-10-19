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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
    /** Bars never get wider than this (a 7-bar week would otherwise draw fat blocks). */
    maxBarWidth: Dp = 22.dp,
    /** The shortest a bar with any listening draws, so it never reads as an empty day. */
    minBarHeight: Dp = 7.dp,
    onBarClick: ((Int) -> Unit)? = null,
    /** The TalkBack action name for a bar ("Open Oct 11 in History"); each bar with a value gets one. */
    barActionLabel: ((Int) -> String)? = null,
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
            .then(
                if (summary.isNotEmpty()) {
                    Modifier.semantics {
                        contentDescription = summary
                        // Taps land on pointer input, which TalkBack can't reach: offer each bar
                        // with a value as a custom action instead.
                        if (onBarClick != null && barActionLabel != null) {
                            customActions = values.indices.filter { values[it] > 0 }.map { i ->
                                CustomAccessibilityAction(barActionLabel(i)) { onBarClick(i); true }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .then(tap),
    ) {
        val n = values.size
        if (n == 0) return@Canvas
        val max = values.max().coerceAtLeast(1L)
        val slot = size.width / n
        val barWidth = (slot * (1f - gapFraction)).coerceIn(1f, maxBarWidth.toPx())
        val stub = 3.dp.toPx()
        // A bar with any listening is always clearly taller than the empty-day stub.
        val minBar = minBarHeight.toPx().coerceAtMost(size.height)
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
                val h = (size.height * v / max).coerceAtLeast(minBar)
                val radius = minOf(barWidth / 2f, h / 2f, 6.dp.toPx())
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
