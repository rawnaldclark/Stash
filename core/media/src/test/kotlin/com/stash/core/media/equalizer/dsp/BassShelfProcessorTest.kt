// BassShelfProcessorTest.kt
package com.stash.core.media.equalizer.dsp

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

  @Test fun `bypasses when bassBoostDb is 0`() {
    val p = BassShelfProcessor(ctrl(EqState(enabled = true, bassBoostDb = 0f)))
    p.configure(AudioFormat(48_000, 1, 4))
    p.flush()
    val s = floatArrayOf(0.3f, -0.3f, 0.3f, -0.3f)
    val out = collect(p, bb(s))
    assertThat(out).usingTolerance(1e-6).containsExactlyElementsIn(s.toTypedArray()).inOrder()
  }

  @Test fun `boosts a 60 Hz sine when bassBoostDb is set`() {
    val p = BassShelfProcessor(ctrl(EqState(enabled = true, bassBoostDb = 12f)))
    p.configure(AudioFormat(48_000, 1, 4))
    p.flush()
    val sr = 48_000
    val sine = FloatArray(8192) { i -> sin(2.0 * PI * 60.0 * i / sr).toFloat() }
    val out = collect(p, bb(sine))
    var peak = 0f
    for (i in 1024 until out.size) peak = maxOf(peak, abs(out[i]))
    assertThat(peak).isGreaterThan(2.5f)
  }

  private fun bb(samples: FloatArray): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 4)
    .order(ByteOrder.nativeOrder()).also { bb -> samples.forEach { bb.putFloat(it) }; bb.flip() }

  private fun collect(p: BassShelfProcessor, input: ByteBuffer): FloatArray {
    p.queueInput(input)
    val o = p.getOutput()
    val list = mutableListOf<Float>()
    while (o.hasRemaining()) list.add(o.float)
    return list.toFloatArray()
  }
}
