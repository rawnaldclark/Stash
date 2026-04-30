// EqProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.stash.core.media.equalizer.EqController
import java.nio.ByteBuffer

/**
 * 5-band peaking EQ. One [Biquad] per band per channel; coefficients
 * recompute when [EqState.gainsDb] or sample rate changes (NEVER per-sample).
 *
 * Bypass when EQ is disabled or all gains are 0 — returns input unchanged.
 */
@OptIn(UnstableApi::class)
class EqProcessor(
  private val controller: EqController,
) : BaseAudioProcessor() {

  private var sampleRate = 0
  private var channels = 0

  private lateinit var filters: Array<Array<Biquad>>
  private var lastAppliedGains: FloatArray = floatArrayOf()

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    sampleRate = inputAudioFormat.sampleRate
    channels = inputAudioFormat.channelCount
    filters = Array(channels) { Array(BAND_FREQS.size) { Biquad() } }
    rebuildCoefficients(controller.state.value.gainsDb)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    if (!state.enabled || isFlat(state.gainsDb)) {
      passthrough(inputBuffer); return
    }
    if (!state.gainsDb.contentEquals(lastAppliedGains)) {
      rebuildCoefficients(state.gainsDb)
    }

    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) {
      for (ch in 0 until channels) {
        var sample = inputBuffer.float
        val chFilters = filters[ch]
        for (band in chFilters.indices) sample = chFilters[band].process(sample)
        out.putFloat(sample)
      }
    }
    out.flip()
  }

  override fun onFlush() {
    if (::filters.isInitialized) filters.forEach { row -> row.forEach { it.reset() } }
  }

  private fun rebuildCoefficients(gains: FloatArray) {
    for (ch in 0 until channels) {
      for (b in BAND_FREQS.indices) {
        filters[ch][b].setPeaking(BAND_FREQS[b], gains[b], BAND_Q, sampleRate)
      }
    }
    lastAppliedGains = gains.copyOf()
  }

  private fun passthrough(inputBuffer: ByteBuffer) {
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
    out.flip()
  }

  private fun isFlat(gains: FloatArray): Boolean = gains.all { it == 0f }

  companion object {
    val BAND_FREQS: FloatArray = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)
    const val BAND_Q = 1f
  }
}
