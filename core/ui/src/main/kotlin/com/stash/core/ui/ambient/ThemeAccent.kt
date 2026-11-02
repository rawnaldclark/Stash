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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.withIndex

/*
 * The album colour in the Material theme: the whole app's `primary` (and its family) follows the
 * current song, paused included, and is neutral with nothing playing (owner decision D9, 2026-11-01).
 *
 * Material's colour scheme is one static CompositionLocal of immutable colours, so swapping it
 * recomposes everything under it (and M3's ColorScheme has no equals, so every new one counts). It is
 * therefore built once per change of colour, never per frame and never per cover file: the palette's
 * colours, its cache key left out ([coloursOnly]: an imported album keeps one cover file per track,
 * all the same colour), are held until they have stayed put for one album crossfade ([settled]), then
 * the scheme is rebuilt from their roles ([withAlbumAccent]) and swapped in one go. What fades smoothly is what already did: the
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
 * A container's least contrast with every page ground it can sit on (the page, a sheet, a dialog), so
 * a selected chip or segment reads as filled next to an unselected one's faint outline (review of #792:
 * the wash alone was ~1.2:1 there).
 */
internal const val CONTAINER_MIN_CONTRAST = 1.6

/** The container's first try: the accent at this alpha over the surface, raised until it stands out. */
private const val CONTAINER_START_ALPHA = 0.35f

/** One step of the container's alpha search. */
private const val CONTAINER_ALPHA_STEP = 0.05f

/**
 * The accent made solid over [surface] at the lowest alpha (from [CONTAINER_START_ALPHA]) that holds
 * [CONTAINER_MIN_CONTRAST] against every one of [grounds]. [accent] is a text-safe colour (4.5:1 on all
 * of them), so the search always ends, at worst at the accent itself.
 */
internal fun containerOver(accent: Color, surface: Color, grounds: List<Color>): Color {
    var alpha = CONTAINER_START_ALPHA
    while (true) {
        val c = compositeOver(accent.copy(alpha = alpha), surface)
        if (alpha >= 1f || grounds.all { contrastRatio(c, it) >= CONTAINER_MIN_CONTRAST }) return c
        alpha = (alpha + CONTAINER_ALPHA_STEP).coerceAtMost(1f)
    }
}

/**
 * Text on [container]: [start] moved away from it until it holds 4.5:1, else the better of the two
 * inks (white or the near-black), which always reads on a container this dark or this light.
 */
private fun textOn(container: Color, start: Color, lighter: Boolean): Color {
    val moved = textSafe(start, listOf(container), lighter, needsInk = false).first
    if (contrastRatio(moved, container) >= SCHEME_TEXT_MIN_CONTRAST) return moved
    return SchemeInks.maxBy { contrastRatio(container, it) }
}

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
 *  - every container = the secondary made solid over the surface, at the least alpha that stands out
 *    [CONTAINER_MIN_CONTRAST] from every page ground ([containerOver]); its "on" colour the primary
 *    moved until it holds 4.5:1 there (a selected chip, segment or FAB);
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
    // The grounds a container sits on: the page, its cards, a sheet (containerLow), a dialog (High).
    val pageGrounds = listOf(
        background, surface, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh,
    )
    val container = containerOver(secondary, surface, pageGrounds)
    val onContainer = textOn(container, primary, lighter)
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
 * colours on Neutral). Changes at most once per change of colour, never per frame. [followsSong] is
 * false on Neutral: the scheme and Home's glow stay neutral, while the player's own chrome (the mini
 * player, Now Playing, the wordmark, the tab bar) keeps following the album as it always did.
 */
@Immutable
data class ThemeAccent(val palette: AmbientPalette, val followsSong: Boolean = true) {
    /** [base]'s grounds wearing this accent on [ground]. Remember it per accent, base and ground. */
    fun scheme(base: ColorScheme, ground: Ground): ColorScheme = base.withAlbumAccent(palette.roles(ground), ground)

    companion object {
        /** Nothing playing: the neutral accent the theme's base schemes are built from (Theme.kt). */
        val Idle = ThemeAccent(AmbientPalette.Default)
    }
}

/**
 * The settled accent the scheme wears, for a surface that builds its own scheme (Now Playing's full
 * art) or follows the Album colours choice (Home's glow). Dynamic, so a change recomposes only those
 * few readers; the scheme itself is what the rest of the app reads.
 */
val LocalThemeAccent = compositionLocalOf { ThemeAccent.Idle }

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
 * [this] palette's colours alone: its cache [AmbientPalette.key] (one per cover file) left out, so two
 * covers of the same colour are the same accent and never rebuild the scheme.
 */
fun AmbientPalette.coloursOnly(): AmbientPalette = if (key.isEmpty()) this else copy(key = "")

/** The palettes the scheme takes: their colours ([coloursOnly]), each once it has [settled]. */
fun Flow<AmbientPalette>.settledColours(settleMs: Long): Flow<AmbientPalette> =
    map { it.coloursOnly() }.settled(settleMs)

/**
 * The app's Material scheme wearing [palette]'s accent once it has [settled] (see the file comment):
 * [MaterialTheme]'s scheme for [content] is the enclosing one's grounds with the album's primary,
 * secondary and tertiary families; with [followSong] false (Album colours: Neutral) it is the idle,
 * neutral accent whatever plays, and a switch either way lands at once. Reads the theme, so it must
 * sit inside StashTheme.
 */
@Composable
fun ProvideThemeAccent(palette: AmbientPalette, followSong: Boolean = true, content: @Composable () -> Unit) {
    val settleMs = if (rememberReducedMotion()) SCHEME_SETTLE_REDUCED_MS else SCHEME_SETTLE_MS
    val wanted = if (followSong) palette else AmbientPalette.Default
    val latest = rememberUpdatedState(wanted)
    // Keyed by the mode too: a new flow's first value lands at once, so the Settings switch is immediate.
    val settledPalette by remember(settleMs, followSong) { snapshotFlow { latest.value }.settledColours(settleMs) }
        .collectAsState(initial = wanted.coloursOnly())
    val accent = remember(settledPalette, followSong) { ThemeAccent(settledPalette, followSong) }
    val ground = currentGround()
    val base = MaterialTheme.colorScheme
    // Keyed on the settled colours, not the accent: switching Album colours rebuilds it once, when the
    // neutral palette lands, not also for the frame where only the choice has changed. A theme flip
    // (base, ground) rebuilds at once, as MaterialTheme switches at once.
    val scheme = remember(settledPalette, base, ground) { accent.scheme(base, ground) }
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
 * so its neutral selected tab reads the theme's own (neutral) primary, not the song's. [followSong]
 * (Settings › Appearance › Album colours) only decides the scheme and Home's glow; the chrome always
 * follows the album.
 */
@Composable
fun ProvideAlbumColours(colors: AlbumColors, followSong: Boolean = true, content: @Composable () -> Unit) {
    ProvideAlbumChrome(colors) {
        ProvideThemeAccent(colors.palette, followSong, content)
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
