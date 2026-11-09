package com.stash.core.model

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Tuning: the concert pitch everything Stash plays is retuned to, as the frequency of the A above
 * middle C (A4). Standard is 440 Hz; 432 Hz retunes every note, harmonic and drum by 432/440, about
 * a third of a semitone lower, at the same speed.
 *
 * The value is stored to 0.1 Hz and kept within [MIN_HZ]..[MAX_HZ], about two semitones either side
 * of standard. Pure, so Settings, Now Playing, the store and the output stage all agree on what a
 * value means and how it reads.
 */
object TuningMath {

    const val STANDARD_HZ = 440f
    const val MIN_HZ = 392f
    const val MAX_HZ = 494f

    /** Settings' preset chips, in the order shown: Standard, 432, 444 ("C = 528"), 442, 415 (Baroque). */
    val PRESETS: List<Float> = listOf(440f, 432f, 444f, 442f, 415f)

    /** Now Playing's short list, before its Custom chip. */
    val QUICK_PRESETS: List<Float> = listOf(440f, 432f, 444f)

    /**
     * [hz] as it can be stored: within range, to the nearest tenth of a hertz. Anything that isn't a
     * number reads as standard.
     */
    fun sanitize(hz: Float): Float {
        if (!hz.isFinite()) return STANDARD_HZ
        return roundToTenth(hz.coerceIn(MIN_HZ, MAX_HZ))
    }

    /** True when [hz] changes the music at all: anything but standard once sanitized. */
    fun isRetuned(hz: Float): Boolean = sanitize(hz) != STANDARD_HZ

    /** What every frequency is multiplied by: 432 Hz gives 0.981818... */
    fun factor(hz: Float): Float = (sanitize(hz).toDouble() / STANDARD_HZ).toFloat()

    /** The offset from standard in cents (hundredths of a semitone): 432 Hz is -31.77. */
    fun cents(hz: Float): Double = 1200.0 * ln(sanitize(hz).toDouble() / STANDARD_HZ) / ln(2.0)

    /**
     * "432" or "431.5": the number alone, one decimal only when it isn't whole, in [locale]'s digits
     * and decimal mark ("431,5" in German, "۴۳۱٫۵" in Persian). [parse] reads all of them back.
     */
    fun number(hz: Float, locale: Locale = Locale.getDefault()): String =
        format(sanitize(hz).toDouble(), locale, minDecimals = 0)

    /** [value] as [number] writes it, for the range and the examples in messages ("392", "431.5"). */
    fun plain(value: Double, locale: Locale = Locale.getDefault()): String = format(value, locale, minDecimals = 0)

    /** "31.8", the size of [cents] to one decimal, for "31.8 cents lower"; [locale] as for [number]. */
    fun centsMagnitude(hz: Float, locale: Locale = Locale.getDefault()): String =
        format(abs(cents(hz)), locale, minDecimals = 1)

    private fun format(value: Double, locale: Locale, minDecimals: Int): String =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = minDecimals
            maximumFractionDigits = 1
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    /** Whether [hz] is below standard (lower), for the words around [centsMagnitude]. */
    fun isLower(hz: Float): Boolean = sanitize(hz) < STANDARD_HZ

    private val HzText = Regex("""[0-9]{0,3}(\.[0-9]?)?""")

    /**
     * The exact-Hz field's text after a keystroke, or null to refuse it: up to three digits and one
     * decimal place. Kept as typed: any script's digits (Persian and Arabic keyboards type their
     * own) and a point, a comma or the Arabic decimal mark (٫) all count; see [parse].
     */
    fun hzInput(new: String): String? = new.takeIf { HzText.matches(normalize(it)) }

    /** [text] with every digit as 0-9 and every decimal mark as a point, for matching and parsing. */
    private fun normalize(text: String): String = buildString(text.length) {
        for (c in text) {
            val d = Character.digit(c, 10)
            append(
                when {
                    d >= 0 -> '0' + d
                    c == ',' || c == ARABIC_DECIMAL_SEPARATOR -> '.'
                    else -> c
                },
            )
        }
    }

    private const val ARABIC_DECIMAL_SEPARATOR = '\u066B'

    /** What the exact-Hz field's text asks for. */
    sealed interface Entry {
        /** Nothing typed yet. */
        data object Empty : Entry

        /** Not a number ("."). */
        data object Invalid : Entry

        /** [hz] is what Set stores; [clamped] when the typed number was outside the range. */
        data class Value(val hz: Float, val clamped: Boolean) : Entry
    }

    fun parse(text: String): Entry {
        val t = normalize(text.trim())
        if (t.isEmpty()) return Entry.Empty
        val v = t.toFloatOrNull()?.takeIf { it.isFinite() } ?: return Entry.Invalid
        return Entry.Value(sanitize(v), clamped = v < MIN_HZ || v > MAX_HZ)
    }

    // In float, as typed: 431.55f times 10 lands on 4315.5f and rounds up to 431.6.
    private fun roundToTenth(v: Float): Float = (v * 10f).roundToInt() / 10f
}
