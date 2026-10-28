// StashRenderersFactory.kt
package com.stash.core.media.equalizer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector  
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider
import com.stash.core.media.equalizer.dsp.FloatDspChain
import com.stash.core.media.output.HiResOutputController
import com.stash.core.media.output.OutputSession
import com.stash.core.media.output.StashAudioOutputProvider


/** Samsung's c2.sec FLAC decoder mislabels 16-bit/44.1 output when float is requested. */
@OptIn(UnstableApi::class)
private val flacSafeSelector = MediaCodecSelector { mimeType, secure, tunneling ->
    val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
    if (mimeType == MimeTypes.AUDIO_FLAC) {
        val safe = all.filterNot { it.name.startsWith("c2.sec.") || it.name.startsWith("OMX.sec.") }
        if (safe.isNotEmpty()) safe else all
    } else {
        all
    }
}
/**
 * RenderersFactory that builds Stash's audio sink: Media3's [DefaultAudioSink]
 * in float, feeding Stash's own output stage
 * ([com.stash.core.media.output.StashAudioOutput]).
 *
 * Why not Media3 AudioProcessors any more: `DefaultAudioSink` only runs
 * user-supplied processors on 16-bit audio — with float output on it routes
 * high-resolution PCM down a branch that skips them. So keeping the EQ meant
 * keeping float off, and every 24-bit FLAC was truncated to 16 bits before
 * the EQ (or the DAC) ever saw it. Now:
 *
 * - **Float output on.** Decoders deliver float (Media3 asks MediaCodec for it
 *   once the sink takes float directly), 24-bit content keeps all 24 bits,
 *   and the AudioTrack is opened at the song's own sample rate.
 * - **The EQ, bass, loudness and limiter run in the output stage**, in float,
 *   only while one of them is actually doing something ([FloatDspChain]).
 *   Off, the samples pass through untouched.
 * - **Speed goes to the AudioTrack** (`setEnableAudioOutputPlaybackParameters`):
 *   Media3's Sonic time-stretcher lives in the processor chain the float
 *   branch skips. AudioFlinger's time-stretch is the same algorithm, applied
 *   instantly (good for Listen Together's drift correction too).
 * - **Hi-res USB**: the output stage asks [HiResOutputController] how to open
 *   each stream — rate-matched or bit-perfect on a USB DAC (Android 14+).
 *
 * One factory per player; [outputSession] carries that player's source format
 * and speed to its output stage.
 *
 * @param mainPlayback true for the playback service's players, false for any
 *   other (the search preview): see [OutputSession.mainPlayback].
 */
@OptIn(UnstableApi::class)
class StashRenderersFactory(
    context: Context,
    private val eqController: EqController,
    private val loudnessController: LoudnessController,
    private val hiResOutputController: HiResOutputController,
    mainPlayback: Boolean,
) : DefaultRenderersFactory(context) {

    /** This factory's player's facts for its output stage (fed by the service's analytics listener). */
    val outputSession = OutputSession(mainPlayback)
    init {
        setMediaCodecSelector(flacSafeSelector)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val audioTrackProvider = AudioTrackAudioOutputProvider.Builder(context)
            .setAudioTrackBufferSizeProvider(OneXBufferSizeProvider)
            .build()
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setAudioOutputProvider(
                StashAudioOutputProvider(audioTrackProvider, hiResOutputController, outputSession) {
                    FloatDspChain(
                        eqState = { eqController.state.value },
                        loudnessState = {
                            // This player's own song gain (see OutputSession.trackGainDb).
                            val shared = loudnessController.state.value
                            val own = outputSession.trackGainDb
                            if (own == null || own == shared.currentTrackGainDb) shared
                            else shared.copy(currentTrackGainDb = own)
                        },
                        // This player's own AutoMix bass-swap shelf; the two
                        // crossfade decks must never share this value.
                        autoMixLowShelfDb = { outputSession.autoMixLowShelfDb },
                    )
                },
            )
            .build()
    }

    /**
     * Media3's 1x buffer size (250–750 ms for PCM), whatever top speed it asks
     * for. Media3 asks for 8x once the AudioTrack time-stretches, and the DSP
     * runs *before* that buffer, so an EQ slider would take seconds to be
     * heard. The output stage doubles the real AudioTrack's buffer above 2x
     * ([com.stash.core.media.output.TrackBufferSizing]); this size stays put,
     * because `DefaultAudioSink` compares configs (buffer size included) to
     * decide whether the next song can keep the track, and one that moved with
     * Listen Together's speed nudges would open a new track at a gapless seam.
     */
    private object OneXBufferSizeProvider : AudioTrackBufferSizeProvider {
        override fun getBufferSizeInBytes(
            minBufferSizeInBytes: Int,
            encoding: Int,
            outputMode: Int,
            pcmFrameSize: Int,
            sampleRate: Int,
            bitrate: Int,
            maxAudioTrackPlaybackSpeed: Double,
        ): Int = AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
            minBufferSizeInBytes,
            encoding,
            outputMode,
            pcmFrameSize,
            sampleRate,
            bitrate,
            /* maxAudioTrackPlaybackSpeed = */ 1.0,
        )
    }
}
