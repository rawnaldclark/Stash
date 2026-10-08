package com.stash.feature.nowplaying.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The three colours the Now Playing ambient paints with, derived from the cover exactly as the
 * web player does: a port of stash-web `player/src/lib/ui/palette.ts` (`paletteFromPixels`,
 * `forScheme`, `DEFAULT_PALETTE`). The accent the rest of the screen uses still comes from
 * Android's Palette; only the ambient follows the web's maths, so both players wash a cover alike.
 */
@Immutable
data class AmbientPalette(
    val dom: Color,
    val vib: Color,
    val mut: Color,
    /** Identity for crossfades; also marks the default (left alone in light). */
    val key: String,
) {
    companion object {
        /** The Stash-purple default (palette.ts DEFAULT_PALETTE). */
        val Default = AmbientPalette(
            dom = Color(0xFF2E1B6E),
            vib = Color(0xFF8B5CF6),
            mut = Color(0xFF3B3358),
            key = "default",
        )
    }
}

private fun clamp(n: Double, lo: Double, hi: Double) = min(hi, max(lo, n))

/** palette.ts hslToHex, rounded to 8-bit channels as the hex string is. */
internal fun hslToColor(h: Double, s: Double, l: Double): Color {
    fun k(n: Double) = (n + h / 30) % 12
    val a = s * min(l, 1 - l)
    fun f(n: Double) = l - a * max(-1.0, min(k(n) - 3, min(9 - k(n), 1.0)))
    fun ch(x: Double) = (x * 255).roundToInt().coerceIn(0, 255)
    return Color(red = ch(f(0.0)), green = ch(f(8.0)), blue = ch(f(4.0)))
}

/** palette.ts rgbToHsl: hue in degrees, s and l in 0..1. */
internal fun rgbToHsl(r0: Int, g0: Int, b0: Int): DoubleArray {
    val r = r0 / 255.0
    val g = g0 / 255.0
    val b = b0 / 255.0
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    val d = mx - mn
    if (d == 0.0) return doubleArrayOf(0.0, 0.0, l)
    val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
    var h = when (mx) {
        r -> (g - b) / d + (if (g < b) 6 else 0)
        g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    }
    h *= 60
    return doubleArrayOf(h, s, l)
}

/**
 * palette.ts `paletteFromPixels`: hue-bucket vote over ARGB pixels (the web reads a 32x32 canvas).
 * Returns null for a (near-)greyscale or empty image; the caller then keeps [AmbientPalette.Default].
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
        if ((p ushr 24) and 0xFF < 128) continue
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
    return AmbientPalette(
        dom = hslToColor(hue, clamp(s, 0.4, 0.8), 0.24),
        vib = hslToColor(hue, clamp(s, 0.55, 0.9), clamp(lum[best] / cnt[best], 0.45, 0.62)),
        mut = hslToColor(hue, 0.18, 0.27),
        key = key,
    )
}

/**
 * palette.ts `forScheme`: on the light ground dom and mut are lifted to mid lightness in the
 * vibrant's hue (a clean tint instead of a brown-grey haze); the default purple is left alone.
 */
fun AmbientPalette.forScheme(light: Boolean): AmbientPalette {
    if (!light || key == AmbientPalette.Default.key) return this
    val v = vib
    val hsl = rgbToHsl((v.red * 255).roundToInt(), (v.green * 255).roundToInt(), (v.blue * 255).roundToInt())
    return copy(
        dom = hslToColor(hsl[0], clamp(hsl[1], 0.5, 0.85), 0.5),
        mut = hslToColor(hsl[0], 0.4, 0.58),
        key = "$key#light",
    )
}
