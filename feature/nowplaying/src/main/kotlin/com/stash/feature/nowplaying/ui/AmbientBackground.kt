package com.stash.feature.nowplaying.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Base dark fill used as the canvas background. */
private val BaseDark = Color(0xFF06060C)

/** Light-theme base: lavender paper matching the app's light ground. */
private val BaseLight = Color(0xFFF4F1FA)

/** Duration of the crossfade when album-art colors change. */
private const val CROSSFADE_MS = 800

/**
 * A full-bleed ambient background that renders three slowly-drifting radial
 * gradients on the theme's canvas. The gradients orbit in circles at
 * different periods (12 s, 16 s, 20 s) creating a subtle, living backdrop
 * that reacts to album-art colors. In [lightMode] the same orbs render as a
 * pastel wash over lavender paper — each palette color is blended a step
 * toward white and drawn at lower alpha so the ground stays airy.
 *
 * @param dominantColor Primary palette color (highest alpha gradient).
 * @param vibrantColor  Secondary palette color.
 * @param mutedColor    Tertiary palette color (lowest alpha gradient).
 * @param lightMode     Pastel wash on lavender paper instead of the dark canvas.
 * @param amoledMode    Pure #000000 canvas, no orbs — and no animation
 *   infrastructure at all: the point of AMOLED is pixels (and the GPU)
 *   doing nothing. Wins over [lightMode].
 * @param modifier      Standard Compose [Modifier].
 */
@Composable
fun AmbientBackground(
    dominantColor: Color,
    vibrantColor: Color,
    mutedColor: Color,
    lightMode: Boolean = false,
    amoledMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // NOTE: no early-return here anymore — amoledMode only swaps the base
    // fill color below. Whether this composable renders at all (i.e.
    // whether the orbs animate) is entirely gated by the caller's
    // ambientAnimationEnabled check in NowPlayingScreen.

    val animDominant by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = CROSSFADE_MS),
        label = "dominantColor",
    )
    val animVibrant by animateColorAsState(
        targetValue = vibrantColor,
        animationSpec = tween(durationMillis = CROSSFADE_MS),
        label = "vibrantColor",
    )
    val animMuted by animateColorAsState(
        targetValue = mutedColor,
        animationSpec = tween(durationMillis = CROSSFADE_MS),
        label = "mutedColor",
    )

    // Infinite angular animations for the three orbital paths.
    val infiniteTransition = rememberInfiniteTransition(label = "ambientOrbit")

    val angle1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit12s",
    )
    val angle2 by infiniteTransition.animateFloat(
        initialValue = 120f, targetValue = 480f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit16s",
    )
    val angle3 by infiniteTransition.animateFloat(
        initialValue = 240f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit20s",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val orbitRadius = min(w, h) * 0.18f
        val gradientRadius = min(w, h) * 0.65f

        // AMOLED wins over light/dark base — true black instead of BaseDark —
        // but the orbs below still draw on top of it.
        drawRect(color = if (amoledMode) Color.Black else if (lightMode) BaseLight else BaseDark)

        // Pastel treatment for the light wash: blend toward white, lower alpha.
        fun orbColor(color: Color, darkAlpha: Float, lightAlpha: Float): Color =
            if (lightMode) {
                lerp(color, Color.White, 0.25f).copy(alpha = lightAlpha)
            } else {
                color.copy(alpha = darkAlpha)
            }

        // Helper: compute orbital center from angle.
        fun orbitalCenter(angleDeg: Float): Offset {
            val rad = Math.toRadians(angleDeg.toDouble())
            return Offset(
                x = cx + (orbitRadius * cos(rad)).toFloat(),
                y = cy + (orbitRadius * sin(rad)).toFloat(),
            )
        }

        // Gradient 1 — dominant, highest presence.
        val center1 = orbitalCenter(angle1)
        drawCircle(
            color = orbColor(animDominant, darkAlpha = 0.35f, lightAlpha = 0.22f),
            radius = gradientRadius,
            center = center1,
        )

        // Gradient 2 — vibrant, medium presence.
        val center2 = orbitalCenter(angle2)
        drawCircle(
            color = orbColor(animVibrant, darkAlpha = 0.25f, lightAlpha = 0.15f),
            radius = gradientRadius * 0.85f,
            center = center2,
        )

        // Gradient 3 — muted, subtle presence.
        val center3 = orbitalCenter(angle3)
        drawCircle(
            color = orbColor(animMuted, darkAlpha = 0.20f, lightAlpha = 0.11f),
            radius = gradientRadius * 0.70f,
            center = center3,
        )
    }
}
