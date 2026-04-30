// PreampProcessorTest.kt
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

class PreampProcessorTest {
  private fun controllerWithState(state: EqState): EqController {
    val ctrl = mockk<EqController>()
    every { ctrl.state } returns MutableStateFlow(state)
    return ctrl
  }

  private fun pcmBuffer(samples: FloatArray): ByteBuffer {
    val bb = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
    samples.forEach { bb.putFloat(it) }
    bb.flip()
    return bb
  }

  @Test fun `disabled state passes input through unchanged`() {
    val p = PreampProcessor(controllerWithState(EqState(enabled = false, preampDb = 6f)))
    p.configure(AudioFormat(48_000, 2, 4 /* PCM_FLOAT */))
    p.flush()
    val input = pcmBuffer(floatArrayOf(0.5f, -0.3f))
    p.queueInput(input)
    val output = p.getOutput()
    val outF = FloatArray(2).also { for (i in it.indices) it[i] = output.float }
    assertThat(outF).usingTolerance(1e-6).containsExactly(0.5f, -0.3f).inOrder()
  }

  @Test fun `enabled with +6 dB doubles amplitude`() {
    val p = PreampProcessor(controllerWithState(EqState(enabled = true, preampDb = 6f)))
    p.configure(AudioFormat(48_000, 2, 4))
    p.flush()
    val input = pcmBuffer(floatArrayOf(0.5f, -0.3f))
    p.queueInput(input)
    val output = p.getOutput()
    val outF = FloatArray(2).also { for (i in it.indices) it[i] = output.float }
    assertThat(outF[0]).isWithin(0.01f).of(0.5f * 1.995f)
    assertThat(outF[1]).isWithin(0.01f).of(-0.3f * 1.995f)
  }
}
