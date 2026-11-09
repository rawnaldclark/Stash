package com.stash.core.media.equalizer.dsp

/**
 * A pitch shift that keeps the tempo, run on interleaved float frames in place: what Tuning puts
 * first in [FloatDspChain].
 *
 * Every call returns as many frames as it was given, delayed by [latencyFrames]: the first
 * [latencyFrames] frames after a [reset] are the engine filling up (silence), and the last
 * [latencyFrames] of a stream are still inside it. Its owner compensates for the delay in the
 * playback position and drains the tail at the end of a stream by feeding [latencyFrames] frames
 * of silence (see [com.stash.core.media.output.StashAudioOutput]).
 *
 * Not thread-safe: one instance per output stream, used on the playback thread.
 */
interface PitchStage {

    /** Input-to-output delay in frames; fixed for the stage's life. */
    val latencyFrames: Int

    /** Multiplies every frequency by [factor] (432/440 for A = 432 Hz); takes effect within a block. */
    fun setFactor(factor: Float)

    /** Processes [frames] interleaved frames of [samples] in place. */
    fun process(samples: FloatArray, frames: Int)

    /** Back to empty (a seek or flush): the next output is delayed by [latencyFrames] again. */
    fun reset()

    /** Frees the engine; the stage does nothing afterwards. */
    fun release()
}

/** Builds a [PitchStage] for one stream, or null when this phone has no engine. */
fun interface PitchStageFactory {
    fun create(sampleRate: Int, channels: Int): PitchStage?

    companion object {
        /** No engine: Tuning stays out of the path. */
        val NONE = PitchStageFactory { _, _ -> null }
    }
}
