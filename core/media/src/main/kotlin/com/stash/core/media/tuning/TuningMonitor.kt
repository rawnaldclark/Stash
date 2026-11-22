package com.stash.core.media.tuning

import com.stash.core.model.tuning.TuningEstimator
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listens to what one player's pitch stage puts out and measures its tuning (true tuning, spec 2026-11-21 §3.4):
 * the check that a song really landed on the chosen pitch, on whatever is playing.
 *
 * [offer] is called on the playback thread with each buffer the stage produced. It only mixes the buffer down to
 * mono into a block; full blocks go to a background thread, where the same estimator that measured the file reads
 * them. Every few seconds of audio the reading so far is passed to [onReading]. [restart] begins again: a new song,
 * or a new factor on the same one. When the background thread falls behind, blocks are dropped, never queued up.
 */
class TuningMonitor(
    private val executor: Executor = sharedExecutor,
    private val onReading: (monitor: TuningMonitor, reading: Reading?) -> Unit,
) {
    /**
     * @param cents the output's offset from the 440 grid, -50 until +50.
     * @param confidence as the estimator gives it.
     * @param seconds how much audio the reading rests on.
     */
    data class Reading(val cents: Double, val confidence: Double, val seconds: Double) {
        val measured: Boolean get() = confidence >= TuningEstimator.MIN_CONFIDENCE
    }

    /** Bumped by [restart]; work from an older one is thrown away. */
    @Volatile private var generation = 0

    // The playback thread's.
    private var block = FloatArray(BLOCK)
    private var filled = 0
    private var blockRate = 0
    private var blockGeneration = -1
    private val queued = AtomicInteger(0)

    // The background thread's.
    private var meter: TuningEstimator.Meter? = null
    private var meterRate = 0
    private var meterGeneration = -1
    private var fed = 0L
    private var sinceReport = 0

    /** Forgets what was heard: the next audio is a new measurement. Any thread. */
    fun restart() {
        generation++
        onReading(this, null)
    }

    /** [frames] interleaved frames of [samples], as the pitch stage left them. Playback thread; doesn't block. */
    fun offer(samples: FloatArray, frames: Int, channels: Int, sampleRate: Int) {
        if (channels <= 0 || sampleRate <= 0) return
        val now = generation
        if (now != blockGeneration || sampleRate != blockRate) {
            filled = 0
            blockGeneration = now
            blockRate = sampleRate
        }
        var at = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += samples[at++]
            block[filled++] = sum / channels
            if (filled == BLOCK) {
                filled = 0
                if (queued.get() >= MAX_QUEUED) continue // behind: this block is dropped and its array reused
                val full = block
                block = FloatArray(BLOCK)
                queued.incrementAndGet()
                executor.execute { feed(full, sampleRate, now) }
            }
        }
    }

    private fun feed(samples: FloatArray, sampleRate: Int, of: Int) {
        try {
            if (of != generation) return
            if (meter == null || meterGeneration != of || meterRate != sampleRate) {
                meter = TuningEstimator.Meter(sampleRate)
                meterGeneration = of
                meterRate = sampleRate
                fed = 0
                sinceReport = 0
            }
            // Long enough to be sure of; past it the reading stands and the meter stops growing.
            if (fed >= MAX_SECONDS * sampleRate) return
            val m = meter ?: return
            m.add(samples)
            fed += samples.size
            sinceReport += samples.size
            if (sinceReport >= REPORT_SECONDS * sampleRate) {
                sinceReport = 0
                val r = m.result()
                if (of == generation) onReading(this, Reading(r.cents, r.confidence, fed.toDouble() / sampleRate))
            }
        } finally {
            queued.decrementAndGet()
        }
    }

    companion object {
        private const val BLOCK = 8_192
        private const val MAX_QUEUED = 8
        private const val REPORT_SECONDS = 4
        private const val MAX_SECONDS = 240L

        /** One low-priority thread for every monitor: measuring must never compete with playback. */
        private val sharedExecutor: Executor = Executors.newSingleThreadExecutor { work ->
            Thread(work, "tuning-monitor").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }

        /**
         * How far a reading is from where [targetHz] puts the notes, in cents, -50 until +50. A tuning is only
         * known within a semitone, so this is the distance to the nearest note of the target's grid.
         */
        fun offCents(readingCents: Double, targetHz: Float): Double {
            val d = readingCents - com.stash.core.model.TuningMath.cents(targetHz)
            return d - 100.0 * Math.floor(d / 100.0 + 0.5)
        }
    }
}
