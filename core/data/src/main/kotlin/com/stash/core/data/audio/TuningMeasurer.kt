package com.stash.core.data.audio

import com.stash.core.model.tuning.TuningEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures a downloaded file's own tuning (true tuning, spec 2026-11-21 §3.1): ffmpeg decodes it to mono
 * [RATE] Hz floats on its stdout, and [TuningEstimator.Meter] reads them as they arrive, so the file is never held
 * in memory. The estimator needs nothing above 2 kHz, and at this rate it does a quarter of the work.
 *
 * One file at a time ([mutex]): a decode takes a whole core.
 */
@Singleton
class TuningMeasurer @Inject constructor(
    private val bridge: FFmpegBridge,
) {
    private val mutex = Mutex()

    sealed class Result {
        /**
         * What the file reads as. A low [confidence] is still a measurement: the song has no steady pitch to find
         * (speech, drums) or two tunings at once, and measuring it again would say the same.
         */
        data class Measured(val cents: Float, val confidence: Float) : Result()

        /** The file couldn't be decoded. [reason] is for the log. */
        data class Failed(val reason: String) : Result()
    }

    /** Never throws but for cancellation: a cancelled measurement is not a broken file. */
    suspend fun measure(file: File): Result = mutex.withLock {
        val meter = TuningEstimator.Meter(RATE)
        val floats = FloatArray(BLOCK / 4)
        // A read can end mid-sample: the odd bytes wait here for the next block.
        val carry = ByteArray(4)
        var carried = 0
        var samples = 0L
        val args = listOf(
            "-nostats", "-hide_banner", "-v", "error",
            "-i", file.absolutePath,
            "-vn", "-ac", "1", "-ar", RATE.toString(),
            "-f", "f32le", "-",
        )
        try {
            bridge.streamStdout(args) { bytes, length ->
                var at = 0
                if (carried > 0) {
                    while (carried < 4 && at < length) carry[carried++] = bytes[at++]
                    if (carried < 4) return@streamStdout
                    floats[0] = ByteBuffer.wrap(carry).order(ByteOrder.LITTLE_ENDIAN).float
                    meter.add(floats, 1)
                    samples++
                    carried = 0
                }
                val whole = (length - at) / 4
                var done = 0
                while (done < whole) {
                    val count = minOf(floats.size, whole - done)
                    ByteBuffer.wrap(bytes, at + done * 4, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats, 0, count)
                    meter.add(floats, count)
                    done += count
                }
                samples += whole
                at += whole * 4
                while (at < length) carry[carried++] = bytes[at++]
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock Result.Failed("ffmpeg failed: ${e.message?.take(300)}")
        }
        if (samples == 0L) return@withLock Result.Failed("no audio decoded")
        val read = meter.result()
        Result.Measured(cents = read.cents.toFloat(), confidence = read.confidence.toFloat())
    }

    private companion object {
        const val RATE = 11_025
        const val BLOCK = 1 shl 16
    }
}
