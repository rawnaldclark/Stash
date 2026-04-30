// PreampProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.stash.core.media.equalizer.EqController
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Master gain stage at the head of the EQ chain.
 *
 * On bypass (`!state.enabled || preampDb == 0`) returns the input buffer
 * unchanged — bit-perfect passthrough.
 *
 * Allocates only at [onConfigure]; the per-buffer hot path is allocation-free.
 */
@OptIn(UnstableApi::class)
class PreampProcessor(
  private val controller: EqController,
) : BaseAudioProcessor() {

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    if (!state.enabled || state.preampDb == 0f) {
      val out = replaceOutputBuffer(inputBuffer.remaining())
      while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
      out.flip()
      return
    }
    val gain = 10f.pow(state.preampDb / 20f)
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) {
      val b0 = inputBuffer.get(); val b1 = inputBuffer.get()
      val b2 = inputBuffer.get(); val b3 = inputBuffer.get()
      val intBits = (b0.toInt() and 0xFF) or
                    ((b1.toInt() and 0xFF) shl 8) or
                    ((b2.toInt() and 0xFF) shl 16) or
                    ((b3.toInt() and 0xFF) shl 24)
      val sample = Float.fromBits(intBits) * gain
      out.putFloat(sample)
    }
    out.flip()
  }
}
