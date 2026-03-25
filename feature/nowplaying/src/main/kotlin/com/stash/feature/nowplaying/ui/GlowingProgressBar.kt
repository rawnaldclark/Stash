package com.stash.feature.nowplaying.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToLong

/** Height of the track bar in dp. */
private val TrackHeight = 4.dp

/** Additional touch target padding above/below the bar. */
private val TouchPadding = 12.dp

/** Radius of the outer thumb circle (visible during drag). */
private const val THUMB_OUTER_RADIUS = 8f

/** Radius of the inner thumb circle (accent fill). */
private const val THUMB_INNER_RADIUS = 5f

/** Radius of the glow circle at the playhead. */
private const val GLOW_RADIUS = 14f

/**
 * A custom glowing progress bar with drag-to-seek support.
 *
 * Renders a gradient-filled track with round caps, a semi-transparent glow
 * at the playhead position, and an optional thumb that appears while the
 * user is dragging. Elapsed and remaining time labels sit below the bar.
 *
 * @param progress    Current playback progress as a fraction in `[0f, 1f]`.
 * @param accentColor Primary accent color used for the gradient and glow.
 * @param elapsedMs   Current playback position in milliseconds.
 * @param totalMs     Total track duration in milliseconds.
 * @param onSeek      Callback invoked with the target position in ms when the
 *                    user taps or finishes dragging.
 * @param modifier    Standard Compose [Modifier].
 */
@Composable
fun GlowingProgressBar(
    progress: Float,
    accentColor: Color,
    elapsedMs: Long,
    totalMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progress) }

    // Smoothly animate progress when not dragging to avoid jitter.
    val displayProgress by animateFloatAsState(
        targetValue = if (isDragging) dragProgress else progress,
        animationSpec = tween(durationMillis = if (isDragging) 0 else 150),
        label = "progressAnim",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TrackHeight + TouchPadding * 2)
                .pointerInput(totalMs) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((fraction * totalMs).roundToLong())
                    }
                }
                .pointerInput(totalMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragProgress * totalMs).roundToLong())
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            dragProgress = (dragProgress + dragAmount / size.width)
                                .coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val barY = size.height / 2f
            val barHeight = TrackHeight.toPx()
            val barWidth = size.width

            // --- Background track ---
            drawRoundRect(
                color = Color.White.copy(alpha = 0.15f),
                topLeft = Offset(0f, barY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f),
            )

            // --- Filled (elapsed) track with gradient ---
            val filledWidth = barWidth * displayProgress
            if (filledWidth > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.6f),
                            accentColor,
                        ),
                        startX = 0f,
                        endX = filledWidth,
                    ),
                    topLeft = Offset(0f, barY - barHeight / 2f),
                    size = Size(filledWidth, barHeight),
                    cornerRadius = CornerRadius(barHeight / 2f),
                )
            }

            // --- Glow at playhead ---
            val playheadX = filledWidth.coerceIn(0f, barWidth)
            drawCircle(
                color = accentColor.copy(alpha = 0.30f),
                radius = GLOW_RADIUS,
                center = Offset(playheadX, barY),
            )

            // --- Thumb (only visible while dragging) ---
            if (isDragging) {
                // Outer white ring.
                drawCircle(
                    color = Color.White,
                    radius = THUMB_OUTER_RADIUS,
                    center = Offset(playheadX, barY),
                )
                // Inner accent fill.
                drawCircle(
                    color = accentColor,
                    radius = THUMB_INNER_RADIUS,
                    center = Offset(playheadX, barY),
                )
            }
        }

        // --- Time labels ---
        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val displayMs = if (isDragging) (dragProgress * totalMs).roundToLong() else elapsedMs
            Text(
                text = formatTime(displayMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = "-${formatTime((totalMs - displayMs).coerceAtLeast(0L))}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Formats milliseconds into `m:ss` or `h:mm:ss` display string.
 */
private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
