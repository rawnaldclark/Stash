package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashBackgroundLight
import com.stash.core.ui.theme.StashSurface
import com.stash.core.ui.theme.StashSurfaceLight

/*
 * The wordmark's one ink, a port of stash-web player/src/lib/ui/palette.ts (`WORDMARK`, `wordmarkInk`;
 * its `contrast`, `mixHex` and `relLum` live in ColorMath.kt). Idle or paused it is the plain ink
 * (white on dark, the light wordmark's aubergine on light); while a song plays it is the plain ink mixed 60 % toward the song's
 * mood colour (textDark, or the light scheme's textLight), stepped back toward the plain ink in 0.05
 * steps until it holds 4.5:1 on every ground it can sit on.
 */

/** The dark wordmark's plain ink. */
val WordmarkPlainDark = Color.White

/** The light wordmark's plain ink (palette.ts WORDMARK.light.plain, the old light wordmark's base fill). */
val WordmarkPlainLight = Color(0xFF1E0742)

/** The grounds the dark wordmark sits on: the app's background, its surface, and AMOLED black. */
val WordmarkBasesDark = listOf(StashBackground, StashSurface, Color.Black)

/** The grounds the light wordmark sits on: the lavender background and white surfaces. */
val WordmarkBasesLight = listOf(StashBackgroundLight, StashSurfaceLight)

/** How far toward the mood colour the wordmark starts (palette.ts `amount`). */
private const val WORDMARK_TINT_AMOUNT = 0.6

/** The step back toward the plain ink while the tint is short of contrast. */
private const val WORDMARK_TINT_STEP = 0.05

/** WCAG AA for the wordmark against each ground. */
private const val WORDMARK_MIN_CONTRAST = 4.5

/**
 * The wordmark's ink for [palette] (null = no song playing: the plain ink).
 *
 * @param light The light theme: the plain ink is [WordmarkPlainLight], the mood is the light scheme's
 *   textLight, and the grounds are [WordmarkBasesLight].
 */
fun wordmarkInk(palette: AmbientPalette?, light: Boolean): Color {
    val plain = if (light) WordmarkPlainLight else WordmarkPlainDark
    if (palette == null) return plain
    val bases = if (light) WordmarkBasesLight else WordmarkBasesDark
    val mood = if (light) palette.forScheme(light = true).textLight else palette.textDark
    var t = WORDMARK_TINT_AMOUNT
    // The web's loop bound (t > 0.001) with its float steps: 0.6, 0.55, ... 0.05.
    while (t > 0.001) {
        val c = mix8(plain, mood, t)
        if (bases.all { contrastRatio(c, it) >= WORDMARK_MIN_CONTRAST }) return c
        t -= WORDMARK_TINT_STEP
    }
    return plain
}
