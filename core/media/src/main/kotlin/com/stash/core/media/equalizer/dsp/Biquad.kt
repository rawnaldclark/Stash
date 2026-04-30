// Biquad.kt
package com.stash.core.media.equalizer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One biquad peaking filter (the atom of our EQ chain).
 *
 * Coefficients are computed via the W3C Audio EQ Cookbook
 * (https://www.w3.org/TR/audio-eq-cookbook/) — the canonical reference for
 * digital filters used by Web Audio, FabFilter, AutoEq, and effectively
 * every audio app on the planet.
 *
 * Per-sample processing uses Direct Form II Transposed for numerical
 * stability and minimal arithmetic.
 *
 * Not thread-safe. One instance per channel.
 */
class Biquad {
  private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
  private var a1 = 0f; private var a2 = 0f
  private var z1 = 0f; private var z2 = 0f

  /** Configure as a peaking EQ band. */
  fun setPeaking(freqHz: Float, gainDb: Float, q: Float, sampleRate: Int) {
    val A = 10.0.pow((gainDb / 40.0)).toFloat()
    val w0 = (2.0 * PI * freqHz / sampleRate).toFloat()
    val cw = cos(w0)
    val alpha = sin(w0) / (2f * q)

    val nb0 = 1f + alpha * A
    val nb1 = -2f * cw
    val nb2 = 1f - alpha * A
    val na0 = 1f + alpha / A
    val na1 = -2f * cw
    val na2 = 1f - alpha / A

    b0 = nb0 / na0
    b1 = nb1 / na0
    b2 = nb2 / na0
    a1 = na1 / na0
    a2 = na2 / na0
  }

  /** Configure as a low-shelf (used by [BassShelfProcessor]). */
  fun setLowShelf(freqHz: Float, gainDb: Float, q: Float, sampleRate: Int) {
    val A = 10.0.pow((gainDb / 40.0)).toFloat()
    val w0 = (2.0 * PI * freqHz / sampleRate).toFloat()
    val cw = cos(w0)
    val sw = sin(w0)
    val alpha = sw / (2f * q)
    val twoSqrtAalpha = 2f * kotlin.math.sqrt(A) * alpha

    val nb0 = A * ((A + 1f) - (A - 1f) * cw + twoSqrtAalpha)
    val nb1 = 2f * A * ((A - 1f) - (A + 1f) * cw)
    val nb2 = A * ((A + 1f) - (A - 1f) * cw - twoSqrtAalpha)
    val na0 = (A + 1f) + (A - 1f) * cw + twoSqrtAalpha
    val na1 = -2f * ((A - 1f) + (A + 1f) * cw)
    val na2 = (A + 1f) + (A - 1f) * cw - twoSqrtAalpha

    b0 = nb0 / na0
    b1 = nb1 / na0
    b2 = nb2 / na0
    a1 = na1 / na0
    a2 = na2 / na0
  }

  /** Process one sample. Direct Form II Transposed. */
  fun process(x: Float): Float {
    val y = b0 * x + z1
    z1 = b1 * x - a1 * y + z2
    z2 = b2 * x - a2 * y
    return y
  }

  /** Clear delay-line state. Call when sample rate changes or chain is reset. */
  fun reset() { z1 = 0f; z2 = 0f }
}
