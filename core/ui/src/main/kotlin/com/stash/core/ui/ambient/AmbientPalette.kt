package com.stash.core.ui.ambient

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where an [AmbientPalette] came from, which decides how its roles are built ([roles]). */
enum class PaletteKind {
    /** No cover, an unreadable one, or nothing playing: Stash purple, the design system's own tokens. */
    BRAND,

    /** A cover with a clear colour: the web's hue vote picked it. */
    COVER,

    /**
     * A cover with no colour to speak of (black and white, sepia, or no hue with a majority): a
     * grey that keeps the image's faint hue, never purple (owner decision D1, 2026-10-18).
     */
    NEUTRAL,
}

/**
 * The album colour, derived from the cover as the web player does: a port of stash-web
 * `player/src/lib/ui/palette.ts` (`paletteFromPixels`, `forScheme`, `DEFAULT_PALETTE`), plus the
 * app's neutral rule for colourless covers ([readCoverPalette]).
 *
 * The three wash colours paint the Now Playing ambient; [roles] turns the palette into the
 * contrast-checked colours everything else wears (the play buttons, the progress lines, text
 * accents), published app-wide through [LocalAlbumChrome]. One palette, one source: the mini player
 * and Now Playing read the same roles, so the play button keeps its shade when the player opens.
 */
@Immutable
data class AmbientPalette(
    val dom: Color,
    val vib: Color,
    val mut: Color,
    /** Identity for crossfades and the cache. */
    val key: String,
    /** --np-text on dark grounds: the song's mood as a light, readable ink. */
    val textDark: Color = DefaultTextDark,
    /** --np-text on light grounds: the same mood as a dark ink ([forScheme] holds it at 5:1 on the wash). */
    val textLight: Color = DefaultTextLight,
    /** Ink on [vib] (palette.ts inkFor): white, or near-black when the accent is bright. */
    val ink: Color = Color.White,
    /** Brand, a coloured cover, or a colourless one; see [PaletteKind]. Required: the roles depend on it. */
    val kind: PaletteKind,
) {
    companion object {
        /** The Stash-purple default (palette.ts DEFAULT_PALETTE). */
        val Default = AmbientPalette(
            dom = Color(0xFF2E1B6E),
            vib = Color(0xFF8B5CF6),
            mut = Color(0xFF3B3358),
            key = "default",
            kind = PaletteKind.BRAND,
        )
    }
}

private val DefaultTextDark = Color(0xFFAE8DF9)
private val DefaultTextLight = Color(0xFF4F3390)

/** palette.ts inkFor (monochrome's rule): YIQ brightness of the accent above 128 takes the dark ink. */
internal fun inkFor(vib: Color): Color {
    val r = (vib.red * 255).roundToInt()
    val g = (vib.green * 255).roundToInt()
    val b = (vib.blue * 255).roundToInt()
    return if ((r * 299 + g * 587 + b * 114) / 1000.0 > 128) InkOnBright else Color.White
}

/** The near-black ink on a bright accent (palette.ts `#1a0b2e`, the light theme's text). */
internal val InkOnBright = Color(0xFF1A0B2E)

private fun clamp(n: Double, lo: Double, hi: Double) = min(hi, max(lo, n))

/** A neutral palette's saturation ceiling: enough to keep a sepia warm, never enough to read as a colour. */
internal const val NEUTRAL_MAX_SATURATION = 0.06

/** Pixels below this alpha are not part of the picture (palette.ts skips them too). */
private const val OPAQUE_ALPHA = 128

/** Below this coherent chroma (see [neutralPaletteFrom]) the pixels lean no way: a plain grey, hue 0. */
private const val NO_HUE_LEAN = 1e-4

/**
 * palette.ts `paletteFromPixels`: hue-bucket vote over ARGB pixels (the web reads a 32x32 canvas).
 * Returns null for a (near-)greyscale or empty image. Web parity only: the app reads covers through
 * [readCoverPalette], which gives a colourless cover the neutral palette instead of null.
 */
fun ambientPaletteFromPixels(argb: IntArray, key: String = "img"): AmbientPalette? {
    val buckets = 12
    val score = DoubleArray(buckets)
    val sx = DoubleArray(buckets)
    val sy = DoubleArray(buckets)
    val sat = DoubleArray(buckets)
    val lum = DoubleArray(buckets)
    val cnt = DoubleArray(buckets)
    var seen = 0
    for (p in argb) {
        if ((p ushr 24) and 0xFF < OPAQUE_ALPHA) continue
        val hsl = rgbToHsl((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]
        seen++
        if (s < 0.18 || l < 0.08 || l > 0.94) continue
        val b = floor(h / (360.0 / buckets)).toInt() % buckets
        // colourful, mid-light pixels vote harder
        val w = s * s * (1 - abs(l - 0.5))
        score[b] += w
        sx[b] += cos(Math.toRadians(h)) * w
        sy[b] += sin(Math.toRadians(h)) * w
        sat[b] += s
        lum[b] += l
        cnt[b]++
    }
    if (seen == 0) return null
    var best = -1
    for (b in 0 until buckets) if (best < 0 || score[b] > score[best]) best = b
    if (best < 0 || cnt[best] < max(6.0, seen * 0.02)) return null
    val hue = (Math.toDegrees(atan2(sy[best], sx[best])) + 360) % 360
    val s = sat[best] / cnt[best]
    val vib = hslToColor(hue, clamp(s, 0.55, 0.9), clamp(lum[best] / cnt[best], 0.45, 0.62))
    return AmbientPalette(
        dom = hslToColor(hue, clamp(s, 0.4, 0.8), 0.24),
        vib = vib,
        mut = hslToColor(hue, 0.18, 0.27),
        key = key,
        textDark = hslToColor(hue, clamp(s + 0.1, 0.55, 0.95), 0.74),
        textLight = hslToColor(hue, 0.55, 0.3),
        ink = inkFor(vib),
        kind = PaletteKind.COVER,
    )
}

/**
 * The cover's palette as the app reads it: the web's hue vote ([ambientPaletteFromPixels]) when a
 * colour wins it ([PaletteKind.COVER]); the neutral palette ([neutralPaletteFrom]) when there are
 * pixels but no colour wins (black and white, sepia, or no hue with a majority); null when nothing in
 * the image is readable (fully transparent, or empty), so the caller keeps the brand default.
 */
fun readCoverPalette(argb: IntArray, key: String = "img"): AmbientPalette? =
    ambientPaletteFromPixels(argb, key) ?: neutralPaletteFrom(argb, key)

/**
 * The neutral palette of a colourless cover: the image's coherent lean, on the cover palette's
 * lightness ladder (dom 0.24, vib from the image's mean lightness held to 0.45..0.62, mut 0.27;
 * inks at 0.80 and 0.30). Null when no pixel is opaque enough to read.
 *
 * The lean is the mean of every pixel's chroma vector (its hue as a direction, its chroma, max - min
 * of the channels, as the length). A sepia's pixels all point one way, so it keeps a warm hint; the
 * random hues of JPEG noise on a black-and-white cover cancel out, so it stays grey. Chroma rather
 * than HSL saturation weights the vote because a near-black or near-white pixel with a few levels of
 * noise has a large HSL saturation but almost no colour. The saturation is the lean's length, held to
 * [NEUTRAL_MAX_SATURATION].
 */
fun neutralPaletteFrom(argb: IntArray, key: String = "img"): AmbientPalette? {
    var seen = 0
    var sx = 0.0
    var sy = 0.0
    var lumSum = 0.0
    for (p in argb) {
        if ((p ushr 24) and 0xFF < OPAQUE_ALPHA) continue
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val hsl = rgbToHsl(r, g, b)
        val chroma = (max(r, max(g, b)) - min(r, min(g, b))) / 255.0
        seen++
        sx += cos(Math.toRadians(hsl[0])) * chroma
        sy += sin(Math.toRadians(hsl[0])) * chroma
        lumSum += hsl[2]
    }
    if (seen == 0) return null
    // A hue only means something when the pixels lean one way; pure greys (or hues that cancel
    // each other out, as noise does) give a plain grey.
    val lean = hypot(sx, sy) / seen
    val leaning = lean >= NO_HUE_LEAN
    val hue = if (leaning) (Math.toDegrees(atan2(sy, sx)) + 360) % 360 else 0.0
    val s = if (leaning) min(lean, NEUTRAL_MAX_SATURATION) else 0.0
    val vib = hslToColor(hue, s, clamp(lumSum / seen, 0.45, 0.62))
    return AmbientPalette(
        dom = hslToColor(hue, s, 0.24),
        vib = vib,
        mut = hslToColor(hue, s, 0.27),
        key = key,
        textDark = hslToColor(hue, s, 0.80),
        textLight = hslToColor(hue, s, 0.30),
        ink = inkFor(vib),
        kind = PaletteKind.NEUTRAL,
    )
}

/**
 * palette.ts `forScheme`: on the light ground dom and mut are lifted to mid lightness in the
 * vibrant's hue (a clean tint instead of a brown-grey haze), and [AmbientPalette.textLight] is
 * darkened in 0.02 lightness steps until it holds 5:1 on that wash (yellows need it); the brand
 * purple is left alone. A neutral palette is lifted the same way but keeps its grey: its saturation
 * never rises above [NEUTRAL_MAX_SATURATION].
 */
fun AmbientPalette.forScheme(light: Boolean): AmbientPalette {
    if (!light || kind == PaletteKind.BRAND) return this
    val hsl = vib.toHsl()
    val neutral = kind == PaletteKind.NEUTRAL
    val neutralSat = min(hsl[1], NEUTRAL_MAX_SATURATION)
    val dom = hslToColor(hsl[0], if (neutral) neutralSat else clamp(hsl[1], 0.5, 0.85), 0.5)
    // The wash: dom at 22 % over white, rounded to 8 bits as the web's hex string is.
    fun wash(c: Float) = ((c * 255).roundToInt() * 0.22 + 255 * 0.78).roundToInt() / 255f
    val washed = Color(wash(dom.red), wash(dom.green), wash(dom.blue))
    val textSat = if (neutral) neutralSat else 0.55
    var l = 0.3
    var text = hslToColor(hsl[0], textSat, l)
    while (l > 0.14 && contrastRatio(washed, text) < 5) {
        l -= 0.02
        text = hslToColor(hsl[0], textSat, l)
    }
    return copy(
        dom = dom,
        mut = hslToColor(hsl[0], if (neutral) neutralSat else 0.4, 0.58),
        key = "$key#light",
        textLight = text,
    )
}
