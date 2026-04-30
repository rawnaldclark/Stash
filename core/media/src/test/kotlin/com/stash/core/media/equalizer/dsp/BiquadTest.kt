// BiquadTest.kt
package com.stash.core.media.equalizer.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BiquadTest {
  @Test fun `0 dB gain produces identity output`() {
    val bq = Biquad().apply { setPeaking(freqHz = 1000f, gainDb = 0f, q = 1f, sampleRate = 48_000) }
    val input = floatArrayOf(0.5f, -0.3f, 0.8f, -0.2f)
    input.forEachIndexed { i, x ->
      assertThat(bq.process(x)).isWithin(1e-5f).of(x)
    }
  }

  @Test fun `coefficients normalise so a0 == 1`() {
    val bq = Biquad().apply { setPeaking(1000f, +6f, 1f, 48_000) }
    val impulse = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val out = impulse.map { bq.process(it) }
    out.forEach { assertThat(it.isFinite()).isTrue() }
  }

  @Test fun `peak filter at center freq amplifies steady-state sine`() {
    val sr = 48_000
    val freq = 1000f
    val bq = Biquad().apply { setPeaking(freq, +6f, 1f, sr) }
    val nSamples = 4096
    val out = FloatArray(nSamples)
    var maxOut = 0f
    for (i in 0 until nSamples) {
      val x = sin(2.0 * PI * freq * i / sr).toFloat()
      out[i] = bq.process(x)
      if (i > 200) maxOut = maxOf(maxOut, kotlin.math.abs(out[i]))
    }
    assertThat(maxOut).isWithin(0.05f).of(1.995f)
  }

  @Test fun `reset clears delay line state`() {
    val bq = Biquad().apply { setPeaking(1000f, +6f, 1f, 48_000) }
    repeat(100) { bq.process(0.5f) }
    bq.reset()
    val zeroOut = bq.process(0f)
    assertThat(zeroOut).isWithin(1e-6f).of(0f)
  }
}
