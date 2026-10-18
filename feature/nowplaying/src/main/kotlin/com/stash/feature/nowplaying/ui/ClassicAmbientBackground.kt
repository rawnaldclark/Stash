package com.stash.feature.nowplaying.ui

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
import com.stash.core.ui.ambient.AlbumChrome
import com.stash.core.ui.ambient.LocalAlbumChrome
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Base dark fill used as the canvas background. */
private val ClassicBaseDark = Color(0xFF06060C)

/** Light-theme base: lavender paper matching the app's light ground. */
private val ClassicBaseLight = Color(0xFFF4F1FA)

/**
 * The "Classic" ambient style (Settings > Appearance > Ambient style): the app's
 * original Now Playing orbs, kept beside the default "Soft glow" ([AmbientBackground],
 * the web player's port), and since 2026-10 painted in the same album colours: the
 * app-wide chrome's dom / vib / mut, crossfading with everything else (no palette of
 * its own any more).
 *
 * A full-bleed ambient background that renders three slowly-drifting radial
 * gradients on the theme's canvas. The gradients orbit in circles at
 * different periods (12 s, 16 s, 20 s) creating a subtle, living backdrop
 * that reacts to album-art colors. In [lightMode] the same orbs render as a
 * pastel wash over lavender paper — each palette color is blended a step
 * toward white and drawn at lower alpha so the ground stays airy.
 *
 * @param lightMode     Pastel wash on lavender paper instead of the dark canvas.
 * @param amoledMode    Pure #000000 canvas, no orbs — and no animation
 *   infrastructure at all: the point of AMOLED is pixels (and the GPU)
 *   doing nothing. Wins over [lightMode].
 * @param modifier      Standard Compose [Modifier].
 * @param colors        The album colours (dom: highest-alpha orb, vib, mut: lowest), read at
 *   draw time so a crossfade only redraws the canvas.
 */
@Composable
fun ClassicAmbientBackground(
    lightMode: Boolean = false,
    amoledMode: Boolean = false,
    modifier: Modifier = Modifier,
    colors: AlbumChrome = LocalAlbumChrome.current,
) {
    // NOTE: no early-return here anymore — amoledMode only swaps the base
    // fill color below. Whether this composable renders at all (i.e.
    // whether the orbs animate) is entirely gated by the caller's
    // ambientAnimationEnabled check in NowPlayingScreen.

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
        drawRect(color = if (amoledMode) Color.Black else if (lightMode) ClassicBaseLight else ClassicBaseDark)

        // Pastel treatment for the light wash: blend toward white, lower alpha.
        fun orbColor(color: Color, darkAlpha: Float, lightAlpha: Float, liftToWhite: Boolean): Color =
            if (lightMode) {
                // dom and mut arrive already lifted for the light ground (the palette's forScheme);
                // only vib, which the lift leaves alone, still takes the old step toward white, so
                // the orbs keep the weight they had over the raw colours.
                (if (liftToWhite) lerp(color, Color.White, 0.25f) else color).copy(alpha = lightAlpha)
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
            color = orbColor(colors.dom, darkAlpha = 0.35f, lightAlpha = 0.22f, liftToWhite = false),
            radius = gradientRadius,
            center = center1,
        )

        // Gradient 2 — vibrant, medium presence.
        val center2 = orbitalCenter(angle2)
        drawCircle(
            color = orbColor(colors.vib, darkAlpha = 0.25f, lightAlpha = 0.15f, liftToWhite = true),
            radius = gradientRadius * 0.85f,
            center = center2,
        )

        // Gradient 3 — muted, subtle presence.
        val center3 = orbitalCenter(angle3)
        drawCircle(
            color = orbColor(colors.mut, darkAlpha = 0.20f, lightAlpha = 0.11f, liftToWhite = false),
            radius = gradientRadius * 0.70f,
            center = center3,
        )
    }
}
