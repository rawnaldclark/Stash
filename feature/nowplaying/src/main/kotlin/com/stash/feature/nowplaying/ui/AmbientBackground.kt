package com.stash.feature.nowplaying.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The Now Playing ambient: a 1:1 port of the web player's big-lyrics ambient,
 * stash-web player/src/lib/styles/fullscreen.css (`.fs-amb`, `.orb`, `.o1`–`.o3`,
 * `@keyframes orbit`, `.fs-amb::after`, the `fs-fade` entrance) with its tokens from
 * player/src/lib/styles/tokens.css (`--fs-base`, `--orb-a/b/c`, `--orbit-1/2/3`,
 * the 800 ms `--np-*` colour transition) and the colours from palette.ts (AmbientPalette.kt).
 *
 * Geometry, in the web's `--os` unit (1 % of the shorter side):
 *  - three orbs, each a soft disc: `radial-gradient(circle closest-side, c 0%, c 30%, transparent)`,
 *    diameters 130 / 110.5 / 91 os, centred 18 os right of the screen centre;
 *  - each orb's layer rotates about the centre, linearly, once per 12 / 16 / 20 s, starting at
 *    0 / 120 / 240 degrees;
 *  - colours: dominant / vibrant / muted at alpha 1.5 x (.35 / .25 / .20) on dark,
 *    1.5 x (.22 / .15 / .11) on light (palette lifted by forScheme), over #06060c / #f4f1fa / #000;
 *  - a top layer: linear-gradient(135deg, transparent 40 %, vibrant at 12 %).
 * The softness is the gradient itself (the web uses no blur filter), so no RenderEffect is needed
 * on any API level. Rotation is a graphicsLayer property on a recorded layer: the compositor turns
 * it every frame without recomposition or re-recording the drawing (the web's compositor
 * `transform: rotate`). Colour changes crossfade over 800 ms FastOutSlowIn in sRGB, as the CSS
 * transition of the registered colours does. With animations off (the Android analogue of
 * prefers-reduced-motion) the orbs stand still at their start angles, the colour fade takes
 * 200 ms and the entrance fade is skipped.
 */

/** --fs-base: AmbientBackground BaseDark. */
private val BaseDark = Color(0xFF06060C)

/** --fs-base on light: lavender paper. */
private val BaseLight = Color(0xFFF4F1FA)

private const val CROSSFADE_MS = 800 // --dur-colour / the --np-* transition
private const val CROSSFADE_REDUCED_MS = 200 // prefers-reduced-motion: transition-duration 200ms
private const val ENTRANCE_MS = 300 // .fs-amb { animation: fs-fade 300ms var(--ease-standard) }

private class Orb(val diameterOs: Float, val startDeg: Float, val periodMs: Int, val darkAlpha: Float, val lightAlpha: Float)

/** .o1 / .o2 / .o3: --a0, --orbit-n, the diameter, and --orb-a/b/c (x 150 % in --oc). */
private val Orbs = listOf(
    Orb(130f, 0f, 12_000, 0.35f * 1.5f, 0.22f * 1.5f),
    Orb(110.5f, 120f, 16_000, 0.25f * 1.5f, 0.15f * 1.5f),
    Orb(91f, 240f, 20_000, 0.20f * 1.5f, 0.11f * 1.5f),
)

/** The orbs' centre sits this far right of the screen centre before rotation. */
private const val ORBIT_OFFSET_OS = 18f

/** Linear interpolation in (unpremultiplied) sRGB, as a CSS transition of a legacy colour. */
private fun mixSrgb(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/** The system "animator duration scale" set to off (Remove animations): the reduced-motion case. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * The palette the ambient last showed, kept for the process: Now Playing gets a new
 * ViewModel each time it opens, and until it has read the cover the ambient starts
 * from these colours (as the web player's palette outlives its views) rather than
 * flashing the default purple. Null until the first cover of the process is read;
 * then the orbs fade in from nothing instead of from the default.
 */
private var lastPalette: AmbientPalette? = null

/**
 * Full-bleed album-colour ambient behind Now Playing. See the file comment for the web source.
 *
 * @param palette    The cover's palette (AmbientPalette, derived as the web's palette.ts does);
 *   null while the cover is still being read (the last palette shown stays).
 * @param lightMode  Lavender paper with the light palette lift and lighter orbs.
 * @param amoledMode Pure black ground (the web's data-black); the orbs still turn over it.
 */
@Composable
fun AmbientBackground(
    palette: AmbientPalette?,
    lightMode: Boolean = false,
    amoledMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReducedMotion()
    val raw = palette ?: lastPalette ?: AmbientPalette.Default
    // Nothing known yet (the first open of the process, cover still loading): the
    // orbs wait, then fade in with the first palette over the colour-fade time.
    val orbsIn = remember { Animatable(if (palette == null && lastPalette == null) 0f else 1f) }
    LaunchedEffect(palette != null) {
        if (palette != null) orbsIn.animateTo(1f, tween(CROSSFADE_MS, easing = FastOutSlowInEasing))
    }
    androidx.compose.runtime.SideEffect { if (palette != null) lastPalette = palette }
    val target = remember(raw, lightMode) { raw.forScheme(lightMode) }

    // Colour crossfade: from the colours on screen now to the new target.
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        val t = mix.value
        from = AmbientPalette(
            dom = mixSrgb(from.dom, to.dom, t),
            vib = mixSrgb(from.vib, to.vib, t),
            mut = mixSrgb(from.mut, to.mut, t),
            key = "mix",
        )
        to = target
        mix.snapTo(0f)
        mix.animateTo(
            1f,
            tween(if (reduceMotion) CROSSFADE_REDUCED_MS else CROSSFADE_MS, easing = FastOutSlowInEasing),
        )
    }

    // Entrance: the ambient fades in over 300 ms when Now Playing opens.
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(ENTRANCE_MS, easing = FastOutSlowInEasing))
    }

    val angles: List<State<Float>> = if (reduceMotion) {
        Orbs.map { remember(it) { mutableStateOf(it.startDeg) } }
    } else {
        val orbit = rememberInfiniteTransition(label = "ambientOrbit")
        Orbs.map { o ->
            orbit.animateFloat(
                initialValue = o.startDeg,
                targetValue = o.startDeg + 360f,
                animationSpec = infiniteRepeatable(tween(o.periodMs, easing = LinearEasing), RepeatMode.Restart),
                label = "orbit${o.periodMs}",
            )
        }
    }

    val base = when {
        amoledMode -> Color.Black
        lightMode -> BaseLight
        else -> BaseDark
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .graphicsLayer { alpha = entrance.value }
            .drawBehind { drawRect(base) },
    ) {
        Box(modifier = Modifier.matchParentSize().graphicsLayer { alpha = orbsIn.value }) {
            Orbs.forEachIndexed { i, orb ->
                val angle = angles[i]
                Box(
                    modifier = Modifier
                        // A square layer about the screen centre, big enough to hold the orb at any
                        // angle, so the rotated drawing never leaves its own bounds.
                        .layout { measurable, constraints ->
                            val os = min(constraints.maxWidth, constraints.maxHeight) / 100f
                            val side = (2 * (ORBIT_OFFSET_OS + orb.diameterOs / 2) * os).roundToInt()
                            val p = measurable.measure(constraints.copy(minWidth = side, maxWidth = side, minHeight = side, maxHeight = side))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                p.place((constraints.maxWidth - side) / 2, (constraints.maxHeight - side) / 2)
                            }
                        }
                        .graphicsLayer { rotationZ = angle.value }
                        .drawBehind {
                            // The side is 2 x (18 + r) os, so the os unit comes back out of it.
                            val os = size.width / (2 * (ORBIT_OFFSET_OS + orb.diameterOs / 2))
                            val r = orb.diameterOs / 2 * os
                            val t = mix.value
                            val raw = when (i) {
                                0 -> mixSrgb(from.dom, to.dom, t)
                                1 -> mixSrgb(from.vib, to.vib, t)
                                else -> mixSrgb(from.mut, to.mut, t)
                            }
                            val c = raw.copy(alpha = if (lightMode) orb.lightAlpha else orb.darkAlpha)
                            val centre = Offset(size.width / 2 + ORBIT_OFFSET_OS * os, size.height / 2)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to c,
                                    0.3f to c,
                                    1f to c.copy(alpha = 0f),
                                    center = centre,
                                    radius = r,
                                ),
                                radius = r,
                                center = centre,
                            )
                        },
                )
            }
            // .fs-amb::after: linear-gradient(135deg, transparent 40%, vibrant 12%).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val vib = mixSrgb(from.vib, to.vib, mix.value).copy(alpha = 0.12f)
                        // CSS gradient line for 135deg: through the centre towards bottom-right,
                        // length |w sin a| + |h cos a| = (w + h) / sqrt 2.
                        val half = (size.width + size.height) / sqrt(2f) / 2f
                        val d = Offset(1f, 1f) / sqrt(2f)
                        val c = Offset(size.width / 2, size.height / 2)
                        drawRect(
                            Brush.linearGradient(
                                0f to vib.copy(alpha = 0f),
                                0.4f to vib.copy(alpha = 0f),
                                1f to vib,
                                start = c - d * half,
                                end = c + d * half,
                            ),
                        )
                    },
            )
        }
    }
}
