package com.stash.core.model.tuning

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures a song's own tuning from its audio (true tuning, spec 2026-11-21 §3.1): how far its notes sit from the
 * standard 12-note grid at A = 440 Hz, in cents, and how sure that is.
 *
 * How: through the song, the steady spectral peaks of each short frame are found to a fraction of a hertz. Each
 * peak's distance from the nearest note of the 440 grid (-50 to +50 cents) goes into a circular histogram, weighted
 * by its strength. Pitched music piles up at one offset, the song's tuning; drums, speech and noise spread evenly.
 * The histogram's peak is the answer, and how much of the weight sits at it is the confidence.
 *
 * A tuning is only known within a semitone: a song a whole semitone down reads as on the grid, which it is.
 *
 * Pure Kotlin, so its accuracy is unit-tested on made audio with known answers (TuningEstimatorTest).
 */
object TuningEstimator {

    /**
     * @param cents the song's offset from the 440 grid, -50 (inclusive) to +50; 432 Hz is -31.77.
     * @param confidence 0 to 1: how much of the pitched energy agrees on that offset. Under [MIN_CONFIDENCE] the
     *   song counts as not measured.
     * @param peaks how many spectral peaks the answer rests on.
     */
    data class Result(val cents: Double, val confidence: Double, val peaks: Int) {
        /** The song's concert pitch: the A nearest 440 on its own grid (427.5 to 452.9 Hz). */
        val hz: Double get() = 440.0 * 2.0.pow(cents / 1200.0)

        val measured: Boolean get() = confidence >= MIN_CONFIDENCE
    }

    /** Below this a song counts as not measured: unpitched, drifting, or too short. Set from the accuracy report. */
    const val MIN_CONFIDENCE = 0.35

    private const val LOW_HZ = 110.0
    private const val HIGH_HZ = 2_000.0

    /** A peak must be within this of its frame's strongest (about -30 dB) to count. */
    private const val PEAK_FLOOR = 0.03
    private const val PEAKS_PER_FRAME = 16

    /** Histogram bins a cent: 0.25-cent steps round the circle. */
    private const val BINS_PER_CENT = 4
    private const val BINS = 100 * BINS_PER_CENT

    /** The histogram is smoothed this wide before its peak is taken, and the answer is refined within [WINDOW_CENTS]. */
    private const val SMOOTH_CENTS = 1.5
    private const val WINDOW_CENTS = 6.0

    /** The share of weight within [WINDOW_CENTS] that reads as fully confident; an even spread has 0.12. */
    private const val FULL_SHARE = 0.55
    private const val EVEN_SHARE = 2 * WINDOW_CENTS / 100.0

    /** Fewer frames with sound than this (about 4 seconds) and the confidence is scaled down: too little to trust. */
    private const val ENOUGH_FRAMES = 40

    /** Harmonics that sit off the 12-note grid (the 3rd is 2 cents sharp, the 5th 14 flat, the 7th 31 flat). */
    private val OFF_GRID_HARMONICS = intArrayOf(3, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15)

    /**
     * The tuning of [samples] (mono, -1 to 1) at [sampleRate]. Silence, or less than one frame, comes back with
     * confidence 0.
     */
    fun estimate(samples: FloatArray, sampleRate: Int): Result {
        val n = Integer.highestOneBit((sampleRate * 0.4).toInt().coerceAtLeast(1024))
        if (samples.size < n) return Result(0.0, 0.0, 0)
        val hop = n / 4
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val mag = DoubleArray(n / 2)
        val lowBin = (LOW_HZ * n / sampleRate).toInt().coerceAtLeast(2)
        val highBin = (HIGH_HZ * n / sampleRate).toInt().coerceAtMost(n / 2 - 2)
        val histogram = DoubleArray(BINS)
        var peakCount = 0
        var soundingFrames = 0
        // The loudest frame sets what counts as silence for the rest.
        var loudest = 0.0
        val frames = ArrayList<List<DoubleArray>>() // per frame: [frequency, strength] of each peak

        var start = 0
        val frameMax = ArrayList<Double>()
        while (start + n <= samples.size) {
            for (i in 0 until n) { re[i] = samples[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            var top = 0.0
            for (k in 0 until n / 2) { mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]); if (k in lowBin..highBin && mag[k] > top) top = mag[k] }
            if (top > loudest) loudest = top
            val peaks = ArrayList<DoubleArray>()
            if (top > 0.0) {
                for (k in lowBin..highBin) {
                    val b = mag[k]
                    if (b < top * PEAK_FLOOR || b <= mag[k - 1] || b < mag[k + 1]) continue
                    // Where the peak really is, between bins: a parabola through the log magnitudes.
                    val la = ln(mag[k - 1] + 1e-12); val lb = ln(b + 1e-12); val lc = ln(mag[k + 1] + 1e-12)
                    val denom = la - 2 * lb + lc
                    val delta = if (denom == 0.0) 0.0 else (0.5 * (la - lc) / denom).coerceIn(-0.5, 0.5)
                    peaks += doubleArrayOf((k + delta) * sampleRate / n, b)
                }
            }
            frames += peaks
            frameMax += top
            start += hop
        }
        if (loudest <= 0.0) return Result(0.0, 0.0, 0)

        for ((f, peaks) in frames.withIndex()) {
            val top = frameMax[f]
            // A frame far quieter than the song's loudest is a gap or a tail: its peaks are mostly noise.
            if (top < loudest * 0.02) continue
            soundingFrames++
            val strongest = peaks.sortedByDescending { it[1] }.take(PEAKS_PER_FRAME)
            for (p in strongest) {
                if (isOffGridHarmonic(p, strongest)) continue
                val cents = 1200.0 * ln(p[0] / 440.0) / ln(2.0)
                val offset = cents - 100.0 * floor(cents / 100.0 + 0.5) // -50 until +50
                var bin = floor((offset + 50.0) * BINS_PER_CENT).toInt()
                if (bin >= BINS) bin = BINS - 1
                if (bin < 0) bin = 0
                histogram[bin] += sqrt(p[1] / top)
                peakCount++
            }
        }
        val total = histogram.sum()
        if (total <= 0.0 || peakCount == 0) return Result(0.0, 0.0, 0)

        // The peak of the smoothed histogram, round the circle.
        val sigma = SMOOTH_CENTS * BINS_PER_CENT
        val reach = (3 * sigma).toInt()
        val kernel = DoubleArray(2 * reach + 1) { exp(-0.5 * ((it - reach) / sigma).pow(2)) }
        var best = 0
        var bestValue = -1.0
        for (b in 0 until BINS) {
            var v = 0.0
            for (j in -reach..reach) v += histogram[Math.floorMod(b + j, BINS)] * kernel[j + reach]
            if (v > bestValue) { bestValue = v; best = b }
        }
        // Refined: the weighted mean of the raw histogram around that peak; and how much of the weight is there.
        val half = (WINDOW_CENTS * BINS_PER_CENT).toInt()
        var weight = 0.0
        var moment = 0.0
        for (j in -half..half) {
            val w = histogram[Math.floorMod(best + j, BINS)]
            weight += w
            moment += w * j
        }
        val centre = best + (if (weight > 0) moment / weight else 0.0)
        var cents = (centre + 0.5) / BINS_PER_CENT - 50.0
        if (cents >= 50.0) cents -= 100.0
        if (cents < -50.0) cents += 100.0
        val share = weight / total
        val agreement = ((share - EVEN_SHARE) / (FULL_SHARE - EVEN_SHARE)).coerceIn(0.0, 1.0)
        val enough = (soundingFrames.toDouble() / ENOUGH_FRAMES).coerceAtMost(1.0)
        return Result(cents, agreement * enough, peakCount)
    }

    /**
     * Whether [peak] is an off-grid harmonic of a lower peak in its frame that is at least as strong: the 3rd, 5th,
     * 7th… of a note sit a few to 31 cents off the grid and would pull the answer. Octaves stay: they're on it.
     */
    private fun isOffGridHarmonic(peak: DoubleArray, frame: List<DoubleArray>): Boolean {
        for (other in frame) {
            if (other[0] >= peak[0] * 0.6 || other[1] < peak[1] * 0.5) continue
            val ratio = peak[0] / other[0]
            for (h in OFF_GRID_HARMONICS) if (abs(ratio - h) < 0.012 * h) return true
        }
        return false
    }

    /** An in-place radix-2 FFT; [re] and [im] have a power-of-two length. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle); val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
