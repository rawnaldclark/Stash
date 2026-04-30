// StashRenderersFactory.kt
package com.stash.core.media.equalizer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.stash.core.media.equalizer.dsp.BassShelfProcessor
import com.stash.core.media.equalizer.dsp.EqProcessor
import com.stash.core.media.equalizer.dsp.PreampProcessor

/**
 * Custom RenderersFactory that builds an audio sink with our EQ chain.
 *
 * The chain is built ONCE per ExoPlayer instance. Toggling EQ enabled is a
 * flag flip read by each processor on every buffer — never a topology
 * change. This is what makes "stacking on re-enable" structurally
 * impossible.
 */
@OptIn(UnstableApi::class)
class StashRenderersFactory(
    context: Context,
    private val eqController: EqController,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val processors: Array<AudioProcessor> = arrayOf(
            PreampProcessor(eqController),
            EqProcessor(eqController),
            BassShelfProcessor(eqController),
        )
        return DefaultAudioSink.Builder(context)
            // setEnableFloatOutput(true) routes high-resolution PCM through
            // a separate Media3 branch that BYPASSES user-supplied processors —
            // verified in DefaultAudioSink source. Keep it false so 24-bit
            // FLAC is downsampled to 16-bit and our chain still applies.
            .setEnableFloatOutput(false)
            .setAudioProcessors(processors)
            .build()
    }
}
