package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.LocalIsAmoledTheme
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.StashSurface
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/*
 * The frosted chrome (the glass mini player and the tab bar), a port of the web player's phone
 * chrome (stash-web player/src/lib/styles/shell.css `.mini`, tokens.css `--bar-bg` / `--bar-ring`):
 * a translucent base over a real backdrop blur of the screen behind it. The scaffold draws every
 * screen edge to edge under the bottom chrome, captures it with Haze's `hazeSource`, and provides
 * that [HazeState] through [LocalChromeHazeState]; screens pad their scrolling content by
 * [LocalBottomChromeInset] so their last row can scroll clear of the glass.
 *
 * Blur needs RenderEffect (API 31+). Below that Haze draws only the fallback tint, which here is
 * near-opaque so text never sits on sharp, unblurred content (the web's no-backdrop-filter
 * fallback is opaque).
 */

/** The backdrop the bottom chrome blurs; null outside the scaffold (previews), where the glass falls back to a tint. */
val LocalChromeHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Whether the chrome glass blurs right now, read at draw time (a state read inside the lambda
 * redraws the glass, it never recomposes it). The app turns it off while the player sheet moves:
 * a page scaling under the glass would cost a fresh blur every frame. Off, the glass keeps its tint
 * over the unblurred page (not Haze's near-opaque fallback, which would flash).
 */
val LocalChromeBlurEnabled = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * How much of the screen's bottom edge the floating chrome covers (mini player, tab bar, banners
 * and the system navigation bar): pad a screen's scrolling content by this so its last row can
 * scroll fully clear. Changes as the mini player comes and goes, so read it where it's used.
 */
val LocalBottomChromeInset = compositionLocalOf { 0.dp }

/**
 * The bottom contentPadding a scrolling screen needs under the glass chrome: [LocalBottomChromeInset]
 * plus the screen's own trailing space [extra]. Also lifts bottom-anchored overlays (a snackbar).
 */
@Composable
@ReadOnlyComposable
fun bottomChromePadding(extra: Dp = 0.dp): Dp = LocalBottomChromeInset.current + extra

/** Glass tokens (shell.css / tokens.css, phone size). */
object ChromeGlassDefaults {
    /** backdrop-filter: blur(20px). */
    val BlurRadius: Dp = 20.dp

    /** --bar-bg on dark: #0d0d18 at 70 %. */
    val TintDark = StashSurface.copy(alpha = 0.70f)

    /** --bar-bg with data-black (AMOLED): #000 at 78 %. */
    val TintAmoled = Color.Black.copy(alpha = 0.78f)

    /** --bar-bg on light: white at 72 %. */
    val TintLight = Color.White.copy(alpha = 0.72f)

    /** --bar-ring on dark: white at 10 %. */
    val RingDark = Color.White.copy(alpha = 0.10f)

    /** --bar-ring on light: #7c3aed at 14 %. */
    val RingLight = Color(0xFF7C3AED).copy(alpha = 0.14f)

    /** Below API 31 (no blur): the surface, nearly opaque. */
    const val FALLBACK_ALPHA = 0.95f
}

/**
 * The chrome glass on this element: clipped to [shape], the blurred screen behind it under the
 * theme's translucent tint, and (with [ring]) the 1dp hairline. Draw the element's content after it.
 *
 * @param tintAlphaScale Multiplies the tint's alpha: the tab bar takes a denser glass than the mini player.
 */
@Composable
fun Modifier.chromeGlass(
    shape: Shape = RectangleShape,
    ring: Boolean = true,
    tintAlphaScale: Float = 1f,
): Modifier {
    val dark = LocalIsDarkTheme.current
    val baseTint = when {
        !dark -> ChromeGlassDefaults.TintLight
        LocalIsAmoledTheme.current -> ChromeGlassDefaults.TintAmoled
        else -> ChromeGlassDefaults.TintDark
    }
    val tint = baseTint.copy(alpha = (baseTint.alpha * tintAlphaScale).coerceAtMost(1f))
    val fallback = MaterialTheme.colorScheme.surface.copy(alpha = ChromeGlassDefaults.FALLBACK_ALPHA)
    // Under the blur: the screen's own ground, so a screen that draws no background still frosts as glass, not as a hole.
    val ground = MaterialTheme.colorScheme.background
    val haze = LocalChromeHazeState.current
    val blurEnabled = LocalChromeBlurEnabled.current
    val glass = if (haze != null) {
        Modifier.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = ground,
                tint = HazeTint(tint),
                blurRadius = ChromeGlassDefaults.BlurRadius,
                noiseFactor = 0f,
                fallbackTint = HazeTint(fallback),
            ),
        ) {
            val on = blurEnabled()
            this.blurEnabled = on && HazeDefaults.blurEnabled()
            // Paused (not unsupported): the translucent tint, so the glass doesn't flash opaque.
            fallbackTint = if (!on && HazeDefaults.blurEnabled()) HazeTint(tint) else HazeTint(fallback)
        }
    } else {
        Modifier.background(fallback)
    }
    val ringColor = if (dark) ChromeGlassDefaults.RingDark else ChromeGlassDefaults.RingLight
    return this
        .clip(shape)
        .then(glass)
        .then(if (ring) Modifier.border(1.dp, ringColor, shape) else Modifier)
}
