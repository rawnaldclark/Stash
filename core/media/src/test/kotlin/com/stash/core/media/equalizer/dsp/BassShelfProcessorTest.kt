// BassShelfProcessorTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BassShelfProcessorTest {
  private fun ctrl(state: EqState) = mockk<EqController>().also {
    every { it.state } returns MutableStateFlow(state)
  }

  private fun pcm16Buffer(samples: ShortArray): ByteBuffer =
    ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).also { bb ->
      samples.forEach { bb.putShort(it) }
      bb.flip()
    }

  @Test fun `bypasses when bassBoostDb is 0`() {
    val p = BassShelfProcessor(ctrl(EqState(enabled = true, bassBoostDb = 0f)))
    p.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
    p.flush()
    val inSamples = shortArrayOf(0x2666, -0x2666, 0x2666, -0x2666)  // ≈ ±0.3 in int16
    val out = collect(p, pcm16Buffer(inSamples))
    assertThat(out).usingTolerance(0.001).containsExactlyElementsIn(
      inSamples.map { it.toFloat() / 32768f }.toTypedArray()
    ).inOrder()
  }

  @Test fun `boosts a 60 Hz sine when bassBoostDb is set`() {
    val p = BassShelfProcessor(ctrl(EqState(enabled = true, bassBoostDb = 12f)))
    p.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
    p.flush()
    val sr = 48_000
    val amp = 0.2f  // small input so +12 dB boost doesn't clip
    val sine = ShortArray(8192) { i ->
      (sin(2.0 * PI * 60.0 * i / sr).toFloat() * amp * 32767f).toInt().toShort()
    }
    val out = collect(p, pcm16Buffer(sine))
    var peak = 0f
    for (i in 1024 until out.size) peak = maxOf(peak, abs(out[i]))
    // +12 dB on amp=0.2 → expected peak ≈ 0.2 * 3.98 = 0.796; assert relative boost > 2.5× input amp
    assertThat(peak).isGreaterThan(amp * 2.5f)
  }

  private fun collect(p: BassShelfProcessor, input: ByteBuffer): FloatArray {
    p.queueInput(input)
    val o = p.getOutput()
    val list = mutableListOf<Float>()
    while (o.hasRemaining()) list.add(o.short.toFloat() / 32768f)
    return list.toFloatArray()
  }
}
