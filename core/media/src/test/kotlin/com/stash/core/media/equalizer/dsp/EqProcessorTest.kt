// EqProcessorTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EqProcessorTest {

  private fun ctrl(state: EqState) = mockk<EqController>().also {
    every { it.state } returns MutableStateFlow(state)
  }

  private fun pcm16Buffer(samples: ShortArray): ByteBuffer =
    ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).also { bb ->
      samples.forEach { bb.putShort(it) }
      bb.flip()
    }

  @Test fun `bypasses when enabled is false`() {
    val p = EqProcessor(ctrl(EqState(enabled = false, gainsDb = floatArrayOf(6f, 0f, 0f, 0f, 0f))))
    p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
    p.flush()
    val inSamples = shortArrayOf(0x4000, -0x4000, 0x4000, -0x4000)
    val out = readAll(p, pcm16Buffer(inSamples))
    // Compare converted float values with tolerance for int16 quantisation.
    assertThat(out).usingTolerance(0.001).containsExactlyElementsIn(
      inSamples.map { it.toFloat() / 32768f }.toTypedArray()
    ).inOrder()
  }

  @Test fun `bypasses when all gains are zero`() {
    val p = EqProcessor(ctrl(EqState(enabled = true, gainsDb = floatArrayOf(0f, 0f, 0f, 0f, 0f))))
    p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
    p.flush()
    val inSamples = shortArrayOf(0x4000, -0x4000)
    val out = readAll(p, pcm16Buffer(inSamples))
    assertThat(out).usingTolerance(0.001).containsExactlyElementsIn(
      inSamples.map { it.toFloat() / 32768f }.toTypedArray()
    ).inOrder()
  }

  @Test fun `boosting band 1 amplifies a sine at 60 Hz mono`() {
    val p = EqProcessor(ctrl(EqState(enabled = true, gainsDb = floatArrayOf(+12f, 0f, 0f, 0f, 0f))))
    p.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
    p.flush()
    val sr = 48_000; val freq = 60.0
    val amp = 0.2f  // small input so +12 dB boost doesn't clip
    val samples = ShortArray(8192) { i ->
      (sin(2.0 * PI * freq * i / sr).toFloat() * amp * 32767f).toInt().toShort()
    }
    p.queueInput(pcm16Buffer(samples))
    val output = p.getOutput()
    val outFloats = FloatArray(samples.size) { output.short.toFloat() / 32768f }
    var maxOut = 0f
    for (i in 1024 until outFloats.size) maxOut = maxOf(maxOut, abs(outFloats[i]))
    // +12 dB on amp=0.2 → expected peak ≈ 0.2 * 3.98 = 0.796
    assertThat(maxOut).isWithin(0.04f).of(0.796f)
  }

  private fun readAll(p: EqProcessor, input: ByteBuffer): FloatArray {
    p.queueInput(input)
    val out = p.getOutput()
    val list = mutableListOf<Float>()
    while (out.hasRemaining()) list.add(out.short.toFloat() / 32768f)
    return list.toFloatArray()
  }
}
