// PreampProcessorTest.kt
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

class PreampProcessorTest {
  private fun controllerWithState(state: EqState): EqController {
    val ctrl = mockk<EqController>()
    every { ctrl.state } returns MutableStateFlow(state)
    return ctrl
  }

  private fun pcm16Buffer(samples: ShortArray): ByteBuffer {
    val bb = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
    samples.forEach { bb.putShort(it) }
    bb.flip()
    return bb
  }

  @Test fun `disabled state passes input through unchanged`() {
    val p = PreampProcessor(controllerWithState(EqState(enabled = false, preampDb = 6f)))
    p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
    p.flush()
    val inSamples = shortArrayOf(0x4000, -0x2000)  // arbitrary mid-range values
    p.queueInput(pcm16Buffer(inSamples))
    val output = p.getOutput()
    val outSamples = ShortArray(2) { output.short }
    assertThat(outSamples.toList()).containsExactly(0x4000.toShort(), (-0x2000).toShort()).inOrder()
  }

  @Test fun `enabled with +6 dB doubles amplitude`() {
    val p = PreampProcessor(controllerWithState(EqState(enabled = true, preampDb = 6f)))
    p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
    p.flush()
    // Use small amplitude so +6 dB boost doesn't clip int16 range.
    val inSamples = shortArrayOf(0x1000, -0x0800)  // 4096, -2048
    p.queueInput(pcm16Buffer(inSamples))
    val output = p.getOutput()
    val outSamples = ShortArray(2) { output.short }
    // +6 dB ≈ 1.995× — apply to inputs and compare with int-quantisation tolerance.
    assertThat(outSamples[0].toInt()).isWithin(50).of((0x1000 * 1.995f).toInt())
    assertThat(outSamples[1].toInt()).isWithin(50).of((-0x0800 * 1.995f).toInt())
  }
}
