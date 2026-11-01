package com.stash.feature.nowplaying.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Radar-style tuning indicator drawn AROUND the radio icon (spec §1,
 * mockup option B). Two layers on one Canvas:
 *
 *  - a faint full "track" ring, always on while [tuning] or [lock] shows;
 *  - a 40° arc that rotates once per ~1.15s while [tuning]; when [lock]
 *    fires (station ready) the arc snaps to a full 360° circle which the
 *    caller fades out by dropping [lock] after [LOCK_HOLD_MS].
 *
 * Runs on every theme including AMOLED — this is icon-local feedback,
 * not an ambient background, so the pure-black no-op convention does
 * not apply.
 */
@Composable
fun RadarSweep(
    tuning: Boolean,
    lock: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // Bail BEFORE creating any animation. The caller composes this whenever
    // a track is showing, so for all but the second or two of actual tuning
    // this composable exists with nothing to draw — and an infinite
    // transition declared above the guard keeps asking the frame clock for
    // callbacks regardless, animating an arc that is never painted for as
    // long as Now Playing is open.
    if (!tuning && !lock) return

    // With motion still (Remove animations, or Battery Saver) the arc holds at the top: the ring and
    // the arc still say "tuning", without a frame per refresh for it. Read in draw, never composition.
    val angle: State<Float> = if (com.stash.core.ui.ambient.rememberReducedMotion()) {
        remember { mutableFloatStateOf(STILL_ARC_DEGREES) }
    } else {
        rememberInfiniteTransition(label = "radarSweep").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1150, easing = LinearEasing)),
            label = "radarAngle",
        )
    }
    // Lock: arc sweeps open to a full circle quickly, then the caller
    // removes the composable (fade handled by state, not alpha here).
    // Tuning always precedes lock in practice, so this state exists before
    // lock flips and still animates 40° -> 360°; a lock with no preceding
    // tuning would simply appear already closed.
    val sweepDegrees by animateFloatAsState(
        targetValue = if (lock) 360f else 40f,
        animationSpec = tween(durationMillis = 220),
        label = "radarSweepDegrees",
    )
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        // Track ring.
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke,
        )
        // Sweep arc (rotates while tuning; full circle on lock).
        drawArc(
            color = color.copy(alpha = if (lock) 0.95f else 0.8f),
            startAngle = if (lock) 0f else angle.value,
            sweepAngle = sweepDegrees,
            useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke,
        )
    }
}

/** How long the locked full circle holds before the caller clears it. */
const val LOCK_HOLD_MS = 250L

/** Where a still arc sits: its 40 degrees centred on the top of the ring (Canvas 0 degrees is 3 o'clock). */
private const val STILL_ARC_DEGREES = 250f
