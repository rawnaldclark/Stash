package com.stash.core.media.equalizer.dsp

import android.util.Log

/**
 * [PitchStage] on Signalsmith Stretch (MIT; native code in `src/main/cpp`): a phase vocoder built
 * for music, which Qt Multimedia also ships for pitch compensation. About 120 ms of latency at
 * 44.1/48 kHz (its default preset) and 100 ms above 48 kHz (its cheaper preset, which keeps the
 * CPU cost of hi-res streams down).
 *
 * [isAvailable] is false when the native library didn't load (an ABI the APK lacks, a broken
 * install): Tuning then shows as unavailable, and nothing crashes.
 */
class SignalsmithPitchStage private constructor(
    handle: Long,
    private val channels: Int,
) : PitchStage {

    @Volatile private var handle: Long = handle

    override val latencyFrames: Int = SignalsmithNative.latency(handle)

    override fun setFactor(factor: Float) {
        val h = handle
        if (h != 0L) SignalsmithNative.setFactor(h, factor)
    }

    override fun process(samples: FloatArray, frames: Int) {
        val h = handle
        if (h == 0L || frames <= 0) return
        require(frames.toLong() * channels <= samples.size) { "$frames frames don't fit ${samples.size} samples" }
        SignalsmithNative.process(h, samples, frames)
    }

    override fun reset() {
        val h = handle
        if (h != 0L) SignalsmithNative.reset(h)
    }

    override fun release() {
        val h = synchronized(this) { handle.also { handle = 0L } }
        if (h != 0L) SignalsmithNative.destroy(h)
    }

    companion object {
        private const val TAG = "Tuning"

        /** Whether the engine's native library loaded on this phone. Loaded on first use. */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("stash_tuning")
                SignalsmithNative.version() > 0
            } catch (t: Throwable) {
                // UnsatisfiedLinkError (no library for this ABI, or a damaged install) and anything
                // else loading can throw: Tuning is then unavailable, never a crash.
                Log.w(TAG, "tuning engine unavailable: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }

        /** A stage for a [sampleRate] x [channels] stream, or null without the engine. */
        fun create(sampleRate: Int, channels: Int): SignalsmithPitchStage? {
            if (!isAvailable) return null
            val h = try {
                SignalsmithNative.create(channels, sampleRate)
            } catch (t: Throwable) {
                Log.w(TAG, "tuning engine refused $sampleRate Hz x $channels: ${t.message}")
                0L
            }
            return if (h == 0L) null else SignalsmithPitchStage(h, channels)
        }
    }
}

/** The JNI surface of `libstash_tuning.so` (stash_tuning_jni.cpp). */
internal object SignalsmithNative {
    @JvmStatic external fun version(): Int
    @JvmStatic external fun create(channels: Int, sampleRate: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun latency(handle: Long): Int
    @JvmStatic external fun setFactor(handle: Long, factor: Float)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun process(handle: Long, samples: FloatArray, frames: Int)
}
