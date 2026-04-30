// BassShelfProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.stash.core.media.equalizer.EqController
import java.nio.ByteBuffer

/**
 * Dedicated low-shelf at 100 Hz, separate from EQ band 1 so bass-boost
 * can be controlled independently without redirecting EQ math.
 *
 * Bypass when bassBoostDb is 0 — returns input unchanged.
 */
@OptIn(UnstableApi::class)
class BassShelfProcessor(
  private val controller: EqController,
) : BaseAudioProcessor() {

  private var sampleRate = 0
  private var channels = 0
  private lateinit var filters: Array<Biquad>
  private var lastAppliedGain = -999f

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    sampleRate = inputAudioFormat.sampleRate
    channels = inputAudioFormat.channelCount
    filters = Array(channels) { Biquad() }
    rebuildIfNeeded(controller.state.value.bassBoostDb, force = true)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    val gain = state.bassBoostDb
    if (!state.enabled || gain == 0f) { passthrough(inputBuffer); return }
    rebuildIfNeeded(gain)
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) {
      for (ch in 0 until channels) {
        out.putFloat(filters[ch].process(inputBuffer.float))
      }
    }
    out.flip()
  }

  override fun onFlush() {
    if (::filters.isInitialized) filters.forEach { it.reset() }
  }

  private fun rebuildIfNeeded(gain: Float, force: Boolean = false) {
    if (!force && gain == lastAppliedGain) return
    for (ch in 0 until channels) filters[ch].setLowShelf(SHELF_FREQ, gain, SHELF_Q, sampleRate)
    lastAppliedGain = gain
  }

  private fun passthrough(inputBuffer: ByteBuffer) {
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
    out.flip()
  }

  companion object {
    const val SHELF_FREQ = 100f
    const val SHELF_Q = 0.7f
  }
}
