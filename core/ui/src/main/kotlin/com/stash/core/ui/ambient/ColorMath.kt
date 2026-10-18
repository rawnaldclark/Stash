package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The colour maths the album palette, its roles and the wordmark share. The hex-level helpers are
 * ports of stash-web player/src/lib/ui/palette.ts (`hslToHex`, `rgbToHsl`, `relLum`, `contrast`,
 * `mixHex`), rounded to 8-bit channels as the web's hex strings are, so both players agree to the bit.
 */

/** An 8-bit sRGB channel, as the web's hex strings carry it. */
internal fun Float.channel(): Int = (this * 255).roundToInt().coerceIn(0, 255)

/** palette.ts hslToHex, rounded to 8-bit channels as the hex string is. [h] in degrees, [s] and [l] in 0..1. */
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

/** [rgbToHsl] of a colour's 8-bit value (alpha ignored). */
internal fun Color.toHsl(): DoubleArray = rgbToHsl(red.channel(), green.channel(), blue.channel())

/** palette.ts relLum: WCAG relative luminance of the colour's 8-bit sRGB value (alpha ignored). */
internal fun relativeLuminance(c: Color): Double {
    fun lin(ch: Int): Double {
        val v = ch / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(c.red.channel()) + 0.7152 * lin(c.green.channel()) + 0.0722 * lin(c.blue.channel())
}

/** WCAG contrast ratio of two opaque colours, 1..21, in either order (palette.ts `contrast`). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** palette.ts mixHex: [a] moved [t] of the way to [b], per 8-bit channel, rounded (alpha dropped). */
internal fun mix8(a: Color, b: Color, t: Double): Color {
    fun ch(x: Float, y: Float) = (x.channel() * (1 - t) + y.channel() * t).roundToInt()
    return Color(red = ch(a.red, b.red), green = ch(a.green, b.green), blue = ch(a.blue, b.blue))
}

/**
 * Linear interpolation in (unpremultiplied) sRGB, alpha included, as a CSS transition of a legacy
 * colour runs (the web's 800 ms `--np-*` crossfade). [t] is clamped to 0..1, so an overshooting
 * easing can never build an out-of-range colour (Compose's Color constructor rejects one).
 */
fun mixSrgb(a: Color, b: Color, t: Float): Color {
    if (t <= 0f) return a
    if (t >= 1f) return b
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )
}

/** [over] painted at its own alpha onto the opaque [base], rounded to 8 bits: what the eye sees there. */
internal fun compositeOver(over: Color, base: Color): Color {
    val a = over.alpha.toDouble()
    fun ch(x: Float, y: Float) = (x.channel() * a + y.channel() * (1 - a)).roundToInt()
    return Color(red = ch(over.red, base.red), green = ch(over.green, base.green), blue = ch(over.blue, base.blue))
}
