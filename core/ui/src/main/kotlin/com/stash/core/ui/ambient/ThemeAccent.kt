package com.stash.core.ui.ambient

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.withIndex

/*
 * The album colour in the Material theme: the whole app's `primary` (and its family) follows the
 * current song, paused included, and is neutral with nothing playing (owner decision D9, 2026-11-01).
 *
 * Material's colour scheme is one static CompositionLocal of immutable colours, so swapping it
 * recomposes everything under it. It is therefore built once per album, never per frame: the palette
 * is held until it has stayed put for one album crossfade ([settled]), then the scheme is rebuilt from
 * its roles ([withAlbumAccent]) and swapped in one go. What fades smoothly is what already did: the
 * chrome read at draw time through [LocalAlbumChrome] (the mini player, Now Playing, the tab bar, the
 * wordmark, Home's glow). The scheme lands as that crossfade ends, and a run of skips lands once.
 *
 * Contrast: Material paints `primary` both as a fill (Button, Switch, Slider) and as text (TextButton,
 * links, the cursor, Settings' section labels), so it is the song's text ink held at 4.5:1 on every
 * surface the scheme declares, never the 3:1 accent. The app wears secondary and tertiary as small
 * text too ("Set up →", "RECENTLY DOWNLOADED"), so they are held to 4.5:1 as well. Grounds are never
 * touched: on pure black the pages stay black and only the accents change.
 */

/** How long a new palette must stay before the scheme takes it: the album crossfade, so it lands as that ends. */
const val SCHEME_SETTLE_MS = ALBUM_CROSSFADE_MS.toLong()

/** The same with animations off (the reduced crossfade). */
const val SCHEME_SETTLE_REDUCED_MS = ALBUM_CROSSFADE_REDUCED_MS.toLong()

/** One step of the scheme's lightness search, as the roles' (AccentRoles.kt). */
private const val SCHEME_LIGHTNESS_STEP = 0.02

/** WCAG AA: text in an accent on a surface, and the ink on a filled accent. */
private const val SCHEME_TEXT_MIN_CONTRAST = 4.5

/** The inks a filled accent can carry: white or the neutral near-black, as the roles' onAccent. */
private val SchemeInks = listOf(Color.White, InkOnBright)

/**
 * [start] as it is when it already holds 4.5:1 on every one of [grounds], else the nearest colour of
 * its hue and saturation away from them (lighter when [lighter]) that does; with [needsInk], one of
 * the two inks must also hold 4.5:1 on it. Returns the colour and its better ink.
 *
 * Moving away from the grounds only raises both contrasts (the near-black ink gains as the colour
 * lightens on a dark scheme, white as it darkens on a light one), so the search always ends: at
 * worst at white (dark grounds) or black (light ones), which pass.
 */
internal fun textSafe(
    start: Color,
    grounds: List<Color>,
    lighter: Boolean,
    needsInk: Boolean = true,
): Pair<Color, Color> {
    fun inkOn(c: Color) = SchemeInks.maxBy { contrastRatio(c, it) }
    fun holds(c: Color): Boolean =
        grounds.all { contrastRatio(c, it) >= SCHEME_TEXT_MIN_CONTRAST } &&
            (!needsInk || contrastRatio(c, inkOn(c)) >= SCHEME_TEXT_MIN_CONTRAST)

    val opaque = start.copy(alpha = 1f)
    if (holds(opaque)) return opaque to inkOn(opaque)
    val (h, s, l0) = opaque.toHsl()
    var l = l0
    while (true) {
        l = (l + if (lighter) SCHEME_LIGHTNESS_STEP else -SCHEME_LIGHTNESS_STEP).coerceIn(0.0, 1.0)
        val c = hslToColor(h, s, l)
        if (holds(c) || l == 0.0 || l == 1.0) return c to inkOn(c)
    }
}

/**
 * This scheme wearing the album accent [roles] (built for [ground]). Only the accent families change;
 * every ground, text colour, error and outline stays as it is:
 *  - primary = the song's text ink ([AccentRoles.ink]) held at 4.5:1 on every surface this scheme
 *    declares (dialogs and sheets included), with onPrimary the better of white and the near-black;
 *  - secondary and tertiary = the song's accent ([AccentRoles.accent]) held the same way (the softer,
 *    more saturated shade), with their ink;
 *  - every container = the wash ([AccentRoles.wash]) made solid on the surface, its "on" colour the
 *    primary (R3 already holds the ink on the wash);
 *  - inversePrimary = the ink held at 4.5:1 on the inverse surface (snackbar actions);
 *  - the fixed roles follow primary and secondary (Material's defaults are its baseline lavender);
 *  - surfaceTint = primary, except on pure black, where it stays black so elevation never lifts a
 *    surface off black (the AMOLED contract, AmoledSchemeTest).
 * Pure and cheap (a few hundred contrast checks): call it once per album and theme.
 */
fun ColorScheme.withAlbumAccent(roles: AccentRoles, ground: Ground): ColorScheme {
    val lighter = !ground.isLight
    val grounds = listOf(
        background, surface, surfaceVariant, surfaceDim, surfaceBright,
        surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
        surfaceContainerHigh, surfaceContainerHighest,
    )
    val (primary, onPrimary) = textSafe(roles.ink, grounds, lighter)
    val (secondary, onSecondary) = textSafe(roles.accent, grounds, lighter)
    val container = compositeOver(roles.wash, surface)
    val onContainer = textSafe(primary, listOf(container), lighter, needsInk = false).first
    val inversePrimary = textSafe(roles.ink, listOf(inverseSurface), !lighter, needsInk = false).first
    return copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = secondary,
        onTertiary = onSecondary,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        surfaceTint = if (ground == Ground.AMOLED) surfaceTint else primary,
        primaryFixed = primary,
        primaryFixedDim = primary,
        onPrimaryFixed = onPrimary,
        onPrimaryFixedVariant = onPrimary,
        secondaryFixed = secondary,
        secondaryFixedDim = secondary,
        onSecondaryFixed = onSecondary,
        onSecondaryFixedVariant = onSecondary,
        tertiaryFixed = secondary,
        tertiaryFixedDim = secondary,
        onTertiaryFixed = onSecondary,
        onTertiaryFixedVariant = onSecondary,
    )
}

/**
 * The accent the app's Material scheme wears: the current song's palette once it has settled
 * ([AmbientPalette.Default], neutral, with nothing playing or with Settings › Appearance › Album
 * colours on Neutral). Changes at most once per album, never per frame.
 */
@Immutable
data class ThemeAccent(val palette: AmbientPalette) {
    /** [base]'s grounds wearing this accent on [ground]. Remember it per accent, base and ground. */
    fun scheme(base: ColorScheme, ground: Ground): ColorScheme = base.withAlbumAccent(palette.roles(ground), ground)

    companion object {
        /** Nothing playing: the neutral accent the theme's base schemes are built from (Theme.kt). */
        val Idle = ThemeAccent(AmbientPalette.Default)
    }
}

/** The settled accent the scheme wears, for a surface that builds its own scheme (Now Playing's full art). */
val LocalThemeAccent = staticCompositionLocalOf { ThemeAccent.Idle }

/**
 * Each value once it has stayed for [settleMs]; the first at once. A value that comes back before
 * the wait is over (A, B, A) emits nothing, and a repeat of the current value never emits: the
 * scheme rebuilds once per album however often the player re-emits it or the user skips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.settled(settleMs: Long): Flow<T> =
    distinctUntilChanged()
        .withIndex()
        .transformLatest { (index, value) ->
            if (index > 0) delay(settleMs)
            emit(value)
        }
        .distinctUntilChanged()

/**
 * The app's Material scheme wearing [palette]'s accent once it has [settled] (see the file comment):
 * [MaterialTheme]'s scheme for [content] is the enclosing one's grounds with the album's primary,
 * secondary and tertiary families. Reads the theme, so it must sit inside StashTheme.
 */
@Composable
fun ProvideThemeAccent(palette: AmbientPalette, content: @Composable () -> Unit) {
    val settleMs = if (rememberReducedMotion()) SCHEME_SETTLE_REDUCED_MS else SCHEME_SETTLE_MS
    val latest = rememberUpdatedState(palette)
    val settledPalette by remember(settleMs) { snapshotFlow { latest.value }.settled(settleMs) }
        .collectAsState(initial = palette)
    val accent = remember(settledPalette) { ThemeAccent(settledPalette) }
    val ground = currentGround()
    val base = MaterialTheme.colorScheme
    // A theme flip (base, ground) rebuilds at once, as MaterialTheme switches at once.
    val scheme = remember(accent, base, ground) { accent.scheme(base, ground) }
    CompositionLocalProvider(LocalThemeAccent provides accent) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/**
 * The app's album colours, both halves: [ProvideAlbumChrome] (the crossfading chrome, read at draw
 * time) around [ProvideThemeAccent] (the Material scheme, once per album). The chrome sits outside
 * so its neutral selected tab reads the theme's own (neutral) primary, not the song's.
 */
@Composable
fun ProvideAlbumColours(colors: AlbumColors, content: @Composable () -> Unit) {
    ProvideAlbumChrome(colors) {
        ProvideThemeAccent(colors.palette, content)
    }
}

/**
 * [base], a dark scheme, wearing the settled album accent for the dark ground: for a surface that is
 * dark whatever the theme (Now Playing's full art), so it never falls back to an idle accent.
 */
@Composable
fun rememberDarkAccentScheme(base: ColorScheme): ColorScheme {
    val accent = LocalThemeAccent.current
    return remember(accent, base) { accent.scheme(base, Ground.DARK) }
}
