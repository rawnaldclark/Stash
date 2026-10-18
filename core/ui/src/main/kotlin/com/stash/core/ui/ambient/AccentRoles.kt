package com.stash.core.ui.ambient

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.stash.core.ui.theme.StashAmoledSurfaceHigh
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashBackgroundLight
import com.stash.core.ui.theme.StashElevatedSurface
import com.stash.core.ui.theme.StashElevatedSurfaceLight
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashPurpleDark
import com.stash.core.ui.theme.StashSurface
import com.stash.core.ui.theme.StashSurfaceLight
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextPrimaryLight
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.core.ui.theme.StashTextSecondaryLight

/*
 * The album colour's roles: one palette turned into the few colours the app actually paints, each
 * checked against every ground it can sit on, so a cover can never make a button vanish or text
 * unreadable. Pure maths (no Compose state): [roles] is called once per palette and theme.
 *
 * The rules (lightness moves in HSL steps of 0.02 with the hue and saturation fixed):
 *  R1 accent holds 3:1 (WCAG non-text) against every base of the ground.
 *  R2 onAccent is the better of white and #1a0b2e and holds 4.5:1 on the accent; when neither does,
 *     the accent keeps moving (R1 still holding). The smallest move that satisfies R1 and R2 wins.
 *     Brand carve-out: the brand keeps the theme's primary/onPrimary pairing, which on dark is
 *     white on #8b5cf6 at 4.23:1 (see below); every cover and neutral palette holds 4.5:1.
 *  R3 ink (text in the song's colour) holds 4.5:1 on every base and on the wash over every base.
 *  R4 wash (the accent as a translucent fill) keeps the ground's text at 7:1 and its secondary text
 *     at 4.5:1 on top of it; its alpha steps down until it does.
 *  R5 pageTint (dom as a translucent page glow) keeps the same text contrast on the background;
 *     none on AMOLED (the black stays black).
 *  R6 a cover accent keeps its own saturation (the vote already holds vib at 0.55..0.9); a neutral
 *     one never rises above NEUTRAL_MAX_SATURATION: a pale grey with dark ink on dark grounds, a
 *     dark grey with white ink on light.
 *  R7 a cover that would need a move of more than 0.35 lightness for R1..R3 takes the neutral roles
 *     instead (its ambient colours stay); the test sweep reports how often that happens.
 * The brand palette is exempt from the search: it wears the design system's own primary and
 * onPrimary, so the idle app looks exactly as it always has.
 */

/** The glass of the mini player and the tab bar on dark (#0d0d18 at 70 %) over the background. */
private val GlassDark = compositeOver(StashSurface.copy(alpha = 0.70f), StashBackground)

/** Now Playing's light paper (its ambient's base, #f4f1fa). */
private val NpPaperLight = Color(0xFFF4F1FA)

/**
 * The ground the app is drawn on: the bases its colours must read against, the text that sits on
 * its tints, and how strong those tints start.
 */
enum class Ground(
    /** Every opaque surface an accent or an ink can sit on in this theme. */
    val bases: List<Color>,
    /** The page background, under the page tint. */
    val background: Color,
    /** Primary text (onSurface / onBackground). */
    val text: Color,
    /** Secondary text (onSurfaceVariant). */
    val textSecondary: Color,
    /** The wash's starting alpha (R4). */
    val washAlpha: Float,
    /** The page tint's starting alpha (R5); zero means no tint at all. */
    val pageTintAlpha: Float,
) {
    DARK(
        bases = listOf(StashBackground, StashSurface, StashElevatedSurface, GlassDark),
        background = StashBackground,
        text = StashTextPrimary,
        textSecondary = StashTextSecondary,
        washAlpha = 0.16f,
        pageTintAlpha = 0.20f,
    ),
    AMOLED(
        bases = listOf(Color.Black, StashAmoledSurfaceHigh),
        background = Color.Black,
        text = StashTextPrimary,
        textSecondary = StashTextSecondary,
        washAlpha = 0.24f,
        pageTintAlpha = 0f,
    ),
    LIGHT(
        bases = listOf(StashBackgroundLight, StashSurfaceLight, StashElevatedSurfaceLight, NpPaperLight),
        background = StashBackgroundLight,
        text = StashTextPrimaryLight,
        textSecondary = StashTextSecondaryLight,
        washAlpha = 0.12f,
        pageTintAlpha = 0.10f,
    ),
    ;

    /** True on the light (cream/lavender) theme: the palette is lifted by [forScheme]. */
    val isLight: Boolean get() = this == LIGHT
}

/**
 * The colours the app paints from one palette on one [Ground]; see the file comment for the rules
 * each one keeps. Built by [roles]; crossfaded by [AccentAnimator]; read through [LocalAlbumChrome].
 */
@Immutable
data class AccentRoles(
    /** The song's accent: play buttons, progress lines, active icons (3:1 on every base). */
    val accent: Color,
    /** Ink on [accent]: white or #1a0b2e, 4.5:1. */
    val onAccent: Color,
    /** Text and small glyphs in the song's colour (4.5:1 on every base and on [wash]). */
    val ink: Color,
    /** The accent as a translucent fill behind text (a playing row, a selected pill). */
    val wash: Color,
    /** The dominant colour as a translucent page glow; transparent on AMOLED. */
    val pageTint: Color,
    /** The Now Playing ambient's three colours, lifted for the light ground. */
    val dom: Color,
    val vib: Color,
    val mut: Color,
)

/** WCAG non-text minimum: an accent against the ground (R1). */
private const val ACCENT_MIN_CONTRAST = 3.0

/** WCAG AA text minimum: ink on its ground, and the onAccent glyph (R2, R3, R4 secondary). */
private const val TEXT_MIN_CONTRAST = 4.5

/** WCAG AAA: the ground's primary text on a wash or a page tint (R4, R5). */
private const val PRIMARY_TEXT_MIN_CONTRAST = 7.0

/** One lightness step of the search. */
private const val LIGHTNESS_STEP = 0.02

/** The furthest the search moves (17 x 0.02 = 0.34, inside R7's 0.35). */
private const val MAX_SHIFT_STEPS = 17

/** The step a wash or a page tint loses while the text on it is short of contrast. */
private const val TINT_ALPHA_STEP = 0.02f

/** A neutral accent's lightness: a pale grey on dark grounds, a dark one on light (R6). */
private const val NEUTRAL_ACCENT_L_DARK = 0.80
private const val NEUTRAL_ACCENT_L_LIGHT = 0.30

/** The two inks an accent can carry (R2). */
private val OnAccentInks = listOf(Color.White, InkOnBright)

/**
 * This palette's roles on [ground]. Brand palettes wear the design system's tokens; cover palettes
 * are searched for the smallest contrast-safe move (falling back to the neutral roles, R7); neutral
 * palettes wear a grey accent. Cheap (a few hundred contrast checks): remember it per palette and ground.
 */
fun AmbientPalette.roles(ground: Ground): AccentRoles = when (kind) {
    PaletteKind.BRAND -> brandRoles(this, ground)
    PaletteKind.NEUTRAL -> neutralRoles(this, ground)
    PaletteKind.COVER -> coverRolesOrNull(ground) ?: coverFallbackRoles(this, ground)
}

/** The idle look on [ground]: Stash purple (the theme's primary for that ground) with white ink. */
fun brandRoles(ground: Ground): AccentRoles = AmbientPalette.Default.roles(ground)

/**
 * A cover palette's roles when R1..R3 can be met within [MAX_SHIFT_STEPS]; null when R7 applies.
 * Internal so the sweep test can count the fallbacks.
 */
internal fun AmbientPalette.coverRolesOrNull(ground: Ground): AccentRoles? {
    val lifted = forScheme(ground.isLight)
    val accent = findAccent(vib, ground) ?: return null
    val wash = washFor(accent.first, ground)
    val ink = findInk(if (ground.isLight) lifted.textLight else textDark, ground, wash) ?: return null
    return AccentRoles(
        accent = accent.first,
        onAccent = accent.second,
        ink = ink,
        wash = wash,
        pageTint = pageTintFor(lifted.dom, ground),
        dom = lifted.dom,
        vib = lifted.vib,
        mut = lifted.mut,
    )
}

/** R7: a cover too extreme to dress safely wears the neutral roles of its own hue; its ambient stays. */
private fun coverFallbackRoles(palette: AmbientPalette, ground: Ground): AccentRoles {
    val hsl = palette.vib.toHsl()
    val s = minOf(hsl[1], NEUTRAL_MAX_SATURATION)
    val twin = palette.copy(
        textDark = hslToColor(hsl[0], s, NEUTRAL_ACCENT_L_DARK),
        textLight = hslToColor(hsl[0], s, NEUTRAL_ACCENT_L_LIGHT),
        vib = hslToColor(hsl[0], s, hsl[2]),
        kind = PaletteKind.NEUTRAL,
    )
    val lifted = palette.forScheme(ground.isLight)
    return neutralRoles(twin, ground).copy(
        pageTint = pageTintFor(lifted.dom, ground),
        dom = lifted.dom,
        vib = lifted.vib,
        mut = lifted.mut,
    )
}

private fun brandRoles(palette: AmbientPalette, ground: Ground): AccentRoles {
    // The theme's own primary for the ground (Theme.kt): StashPurple on dark, the deeper
    // StashPurpleDark on light, both under white, as every brand button in the app is.
    val accent = if (ground.isLight) StashPurpleDark else StashPurple
    val wash = washFor(accent, ground)
    val inkStart = if (ground.isLight) palette.textLight else palette.textDark
    return AccentRoles(
        accent = accent,
        onAccent = Color.White,
        ink = findInk(inkStart, ground, wash) ?: inkStart,
        wash = wash,
        pageTint = pageTintFor(palette.dom, ground),
        dom = palette.dom,
        vib = palette.vib,
        mut = palette.mut,
    )
}

private fun neutralRoles(palette: AmbientPalette, ground: Ground): AccentRoles {
    val lifted = palette.forScheme(ground.isLight)
    val hsl = palette.vib.toHsl()
    val s = minOf(hsl[1], NEUTRAL_MAX_SATURATION)
    val start = hslToColor(hsl[0], s, if (ground.isLight) NEUTRAL_ACCENT_L_LIGHT else NEUTRAL_ACCENT_L_DARK)
    // A grey at these lightnesses always passes (the test sweep proves it); the fallbacks only
    // guard the contract, they are never taken.
    val (accent, onAccent) = findAccent(start, ground)
        ?: (start to OnAccentInks.maxBy { contrastRatio(start, it) })
    val wash = washFor(accent, ground)
    val inkStart = if (ground.isLight) lifted.textLight else palette.textDark
    return AccentRoles(
        accent = accent,
        onAccent = onAccent,
        ink = findInk(inkStart, ground, wash) ?: inkStart,
        wash = wash,
        pageTint = pageTintFor(lifted.dom, ground),
        dom = lifted.dom,
        vib = lifted.vib,
        mut = lifted.mut,
    )
}

/**
 * R1 + R2: the accent closest to [start] (by lightness, hue and saturation kept) that holds 3:1 on
 * every base and carries an ink at 4.5:1, with that ink. Ties go away from the ground (lighter on
 * dark, darker on light). Null when nothing within [MAX_SHIFT_STEPS] does.
 */
private fun findAccent(start: Color, ground: Ground): Pair<Color, Color>? {
    val (h, s, l0) = start.toHsl()
    val away = if (ground.isLight) -1 else 1
    for (k in 0..MAX_SHIFT_STEPS) {
        for (dir in if (k == 0) intArrayOf(away) else intArrayOf(away, -away)) {
            val l = l0 + dir * k * LIGHTNESS_STEP
            if (l < 0.0 || l > 1.0) continue
            val c = hslToColor(h, s, l)
            if (ground.bases.any { contrastRatio(c, it) < ACCENT_MIN_CONTRAST }) continue
            val on = OnAccentInks.maxBy { contrastRatio(c, it) }
            if (contrastRatio(c, on) >= TEXT_MIN_CONTRAST) return c to on
        }
    }
    return null
}

/**
 * R3: [start] moved away from the ground (lighter on dark, darker on light) until it holds 4.5:1
 * on every base and on [wash] over every base. Null when nothing within [MAX_SHIFT_STEPS] does.
 */
private fun findInk(start: Color, ground: Ground, wash: Color): Color? {
    val (h, s, l0) = start.toHsl()
    val away = if (ground.isLight) -1 else 1
    val grounds = ground.bases + ground.bases.map { compositeOver(wash, it) }
    for (k in 0..MAX_SHIFT_STEPS) {
        val l = l0 + away * k * LIGHTNESS_STEP
        if (l < 0.0 || l > 1.0) break
        val c = hslToColor(h, s, l)
        if (grounds.all { contrastRatio(c, it) >= TEXT_MIN_CONTRAST }) return c
    }
    return null
}

/** R4: the accent at the ground's wash alpha, thinned until the ground's text still reads on it. */
private fun washFor(accent: Color, ground: Ground): Color =
    tintHoldingText(accent, ground.washAlpha, ground, ground.bases)

/** R5: dom at the ground's page-tint alpha over the background; transparent (dom at 0) on AMOLED. */
private fun pageTintFor(dom: Color, ground: Ground): Color =
    tintHoldingText(dom, ground.pageTintAlpha, ground, listOf(ground.background))

/**
 * [colour] at [startAlpha], stepped down until the ground's primary text holds 7:1 and its
 * secondary text 4.5:1 on it over each of [over]. Keeps the colour's own RGB at alpha 0, so a
 * crossfade into or out of "no tint" never passes through black.
 */
private fun tintHoldingText(colour: Color, startAlpha: Float, ground: Ground, over: List<Color>): Color {
    var alpha = startAlpha
    while (alpha > 0f) {
        val tint = colour.copy(alpha = alpha)
        val holds = over.all { base ->
            val c = compositeOver(tint, base)
            contrastRatio(ground.text, c) >= PRIMARY_TEXT_MIN_CONTRAST &&
                contrastRatio(ground.textSecondary, c) >= TEXT_MIN_CONTRAST
        }
        if (holds) return tint
        alpha -= TINT_ALPHA_STEP
    }
    return colour.copy(alpha = 0f)
}

/** Every role of [a] moved [t] of the way to [b] in sRGB (see [mixSrgb]). */
fun lerpRoles(a: AccentRoles, b: AccentRoles, t: Float): AccentRoles = when {
    t <= 0f -> a
    t >= 1f -> b
    else -> AccentRoles(
        accent = mixSrgb(a.accent, b.accent, t),
        onAccent = mixSrgb(a.onAccent, b.onAccent, t),
        ink = mixSrgb(a.ink, b.ink, t),
        wash = mixSrgb(a.wash, b.wash, t),
        pageTint = mixSrgb(a.pageTint, b.pageTint, t),
        dom = mixSrgb(a.dom, b.dom, t),
        vib = mixSrgb(a.vib, b.vib, t),
        mut = mixSrgb(a.mut, b.mut, t),
    )
}
