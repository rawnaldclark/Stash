// EqProcessorTest.kt
package com.stash.core.media.equalizer.dsp

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

  private fun bufFromSamples(samples: FloatArray): ByteBuffer =
    ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder()).also { bb ->
      samples.forEach { bb.putFloat(it) }
      bb.flip()
    }

  @Test fun `bypasses when enabled is false`() {
    val p = EqProcessor(ctrl(EqState(enabled = false, gainsDb = floatArrayOf(6f, 0f, 0f, 0f, 0f))))
    p.configure(AudioFormat(48_000, 2, 4))
    p.flush()
    val samples = floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f)
    val out = readAll(p, bufFromSamples(samples))
    assertThat(out).usingTolerance(1e-6).containsExactlyElementsIn(samples.toTypedArray()).inOrder()
  }

  @Test fun `bypasses when all gains are zero`() {
    val p = EqProcessor(ctrl(EqState(enabled = true, gainsDb = floatArrayOf(0f,0f,0f,0f,0f))))
    p.configure(AudioFormat(48_000, 2, 4))
    p.flush()
    val samples = floatArrayOf(0.5f, -0.5f)
    val out = readAll(p, bufFromSamples(samples))
    assertThat(out).usingTolerance(1e-6).containsExactlyElementsIn(samples.toTypedArray()).inOrder()
  }

  @Test fun `boosting band 1 amplifies a sine at 60 Hz mono`() {
    val p = EqProcessor(ctrl(EqState(enabled = true, gainsDb = floatArrayOf(+12f, 0f, 0f, 0f, 0f))))
    p.configure(AudioFormat(48_000, 1, 4))
    p.flush()
    val sr = 48_000; val freq = 60.0
    val samples = FloatArray(8192) { i -> sin(2.0 * PI * freq * i / sr).toFloat() }
    val out = readAll(p, bufFromSamples(samples))
    var maxOut = 0f
    for (i in 1024 until out.size) maxOut = maxOf(maxOut, abs(out[i]))
    assertThat(maxOut).isWithin(0.2f).of(3.98f)
  }

  private fun readAll(p: EqProcessor, input: ByteBuffer): FloatArray {
    p.queueInput(input)
    val out = p.getOutput()
    val list = mutableListOf<Float>()
    while (out.hasRemaining()) list.add(out.float)
    return list.toFloatArray()
  }
}
