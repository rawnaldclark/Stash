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
 * Operates on `PCM_16BIT` because that's what Media3's decoder pipeline
 * actually delivers to user-supplied AudioProcessors for MP3/Opus/M4A
 * content. (PCM_FLOAT is only used by Media3's internal high-resolution
 * branch, which bypasses user processors entirely — see
 * `DefaultAudioSink.shouldUseFloatOutput`.)
 *
 * Conversion: read int16 → divide by 32768 to get float in [-1, 1] →
 * multiply by gain → clamp → write back as int16. The biquad math we use
 * downstream needs float for numerical stability; we pay one cheap
 * conversion at each end of the chain.
 *
 * On bypass (`!state.enabled || preampDb == 0`) the bytes are copied
 * straight through — bit-perfect passthrough.
 */
@OptIn(UnstableApi::class)
class PreampProcessor(
  private val controller: EqController,
) : BaseAudioProcessor() {

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    android.util.Log.i("EqDsp", "Preamp.onConfigure: encoding=${inputAudioFormat.encoding} (PCM_16BIT=${C.ENCODING_PCM_16BIT}) sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}")
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    return inputAudioFormat
  }

  private var loggedFirstBuffer = false
  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    if (!loggedFirstBuffer) {
      android.util.Log.i("EqDsp", "Preamp.queueInput[FIRST]: enabled=${state.enabled} preampDb=${state.preampDb} bytes=${inputBuffer.remaining()}")
      loggedFirstBuffer = true
    }
    if (!state.enabled || state.preampDb == 0f) {
      val out = replaceOutputBuffer(inputBuffer.remaining())
      while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
      out.flip()
      return
    }
    val gain = 10f.pow(state.preampDb / 20f)
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.remaining() >= 2) {
      val sample = inputBuffer.short.toFloat() / 32768f
      val gained = (sample * gain).coerceIn(-1f, 1f)
      out.putShort((gained * 32767f).toInt().toShort())
    }
    out.flip()
  }
}
