package com.stash.core.media.equalizer.dsp

/**
 * A pure-Kotlin [PitchStage] for JVM tests: no pitch change, just the engine's defining trait, a
 * fixed delay of [latencyFrames] frames (zeros first, then the input). Lets the output stage's
 * latency handling (position, end-of-stream tail, flush) be checked sample for sample.
 */
class FixedDelayFakeStage(private val channels: Int, override val latencyFrames: Int) : PitchStage {
    private val line = FloatArray(latencyFrames * channels)
    private var pos = 0

    var factor = 1f
        private set
    var resets = 0
        private set
    var released = false
        private set
    var processedFrames = 0L
        private set

    override fun setFactor(factor: Float) {
        this.factor = factor
    }

    override fun process(samples: FloatArray, frames: Int) {
        check(!released) { "process after release" }
        processedFrames += frames
        if (line.isEmpty()) return
        for (i in 0 until frames * channels) {
            val delayed = line[pos]
            line[pos] = samples[i]
            samples[i] = delayed
            pos = (pos + 1) % line.size
        }
    }

    override fun reset() {
        line.fill(0f)
        pos = 0
        resets++
    }

    override fun release() {
        released = true
    }
}
