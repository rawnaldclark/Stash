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
 * How: the audio is brought down to about 11 kHz, then the spectral peaks of each short frame are found to a
 * fraction of a hertz. Only the steady ones count: a peak that holds its pitch for about half a second, which is
 * what a played note does and what drums, speech and a singer's slides don't. Each steady peak's distance from the
 * nearest note of the 440 grid (-50 to +50 cents) goes into a circular histogram. Pitched music piles up at one
 * offset, the song's tuning; everything else spreads evenly. The histogram's peak is the answer. How far it stands
 * above the rest, and whether a second peak rivals it, is the confidence.
 *
 * A tuning is only known within a semitone: a song a whole semitone down reads as on the grid, which it is.
 *
 * Pure Kotlin, so its accuracy is unit-tested on made audio with known answers (TuningEstimatorTest).
 */
object TuningEstimator {

    /**
     * @param cents the song's offset from the 440 grid, -50 (inclusive) to +50; 432 Hz is -31.77.
     * @param confidence 0 to 1: how clearly the steady notes agree on that offset. Under [MIN_CONFIDENCE] the song
     *   counts as not measured.
     * @param peaks how many steady spectral peaks the answer rests on.
     */
    data class Result(val cents: Double, val confidence: Double, val peaks: Int) {
        /** The song's concert pitch: the A nearest 440 on its own grid (427.5 to 452.9 Hz). */
        val hz: Double get() = 440.0 * 2.0.pow(cents / 1200.0)

        val measured: Boolean get() = confidence >= MIN_CONFIDENCE
    }

    /** Below this a song counts as not measured: unpitched, drifting, two tunings at once, or too short. */
    const val MIN_CONFIDENCE = 0.35

    private const val LOW_HZ = 110.0
    private const val HIGH_HZ = 2_000.0

    /** The audio is analysed at about this rate: everything under [HIGH_HZ] is kept, at a quarter of the work. */
    private const val WORK_RATE = 11_025

    /** A peak must be within this of its frame's strongest (about -30 dB) to count. */
    private const val PEAK_FLOOR = 0.03
    private const val PEAKS_PER_FRAME = 16

    /** A steady peak has one within [STEADY_CENTS] of it in each of the [STEADY_FRAMES] frames before and after. */
    private const val STEADY_FRAMES = 2
    private const val STEADY_CENTS = 4.0

    /** Histogram bins a cent: 0.25-cent steps round the circle. */
    private const val BINS_PER_CENT = 4
    private const val BINS = 100 * BINS_PER_CENT

    /** The histogram is smoothed this wide before its peak is taken, and the answer is refined within [WINDOW_CENTS]. */
    private const val SMOOTH_CENTS = 1.5
    private const val WINDOW_CENTS = 6.0

    /**
     * How far the peak stands above the histogram's median. Speech and noise read about 1; the real songs measured
     * for the accuracy report read 5.7 to 70.
     */
    private const val RATIO_NONE = 2.0
    private const val RATIO_FULL = 6.0

    /**
     * A second peak, more than [RIVAL_CENTS] from the first: at [RIVAL_FULL] of the first's height the song has two
     * tunings and neither is trusted; under [RIVAL_NONE] it doesn't count against the first.
     */
    private const val RIVAL_CENTS = 15.0
    private const val RIVAL_NONE = 0.6
    private const val RIVAL_FULL = 0.9

    /** Fewer frames with steady notes than this (about 4 seconds) and the confidence is scaled down. */
    private const val ENOUGH_FRAMES = 80

    /** Harmonics that sit off the 12-note grid (the 3rd is 2 cents sharp, the 5th 14 flat, the 7th 31 flat). */
    private val OFF_GRID_HARMONICS = intArrayOf(3, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15)

    /**
     * The tuning of [samples] (mono, -1 to 1) at [sampleRate]. Silence, or less than one frame, comes back with
     * confidence 0.
     */
    fun estimate(samples: FloatArray, sampleRate: Int): Result =
        Meter(sampleRate).apply { add(samples, samples.size) }.result()

    /**
     * Measures audio as it arrives, so a file is never held whole and a stream can be read while it plays: [add]
     * each block of mono samples, then ask for the [result] (as often as wanted; more audio can follow).
     * It keeps about 3 KB for each second heard. Not thread-safe.
     */
    class Meter(sampleRate: Int) {
        private val step = (sampleRate.coerceAtLeast(1) / WORK_RATE).coerceAtLeast(1)
        private val rate = sampleRate.coerceAtLeast(1).toDouble() / step
        private val n = Integer.highestOneBit((rate * 0.4).toInt().coerceAtLeast(1024))
        private val hop = n / 8
        private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        private val re = DoubleArray(n)
        private val im = DoubleArray(n)
        private val mag = DoubleArray(n / 2)
        private val lowBin = (LOW_HZ * n / rate).toInt().coerceAtLeast(2)
        private val highBin = (HIGH_HZ * n / rate).toInt().coerceAtMost(n / 2 - 2)

        // The low-pass before every [step]th sample is kept: a windowed sinc that keeps what is under HIGH_HZ and
        // removes what would fold back onto it.
        private val half = 8 * step
        private val taps = DoubleArray(2 * half + 1) { i ->
            val cutoff = 0.4 / step // of the sample rate: 80% of the new band's edge
            val x = (i - half).toDouble()
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            sinc * (0.42 - 0.5 * cos(2 * PI * i / (2 * half)) + 0.08 * cos(4 * PI * i / (2 * half)))
        }.let { t -> val gain = t.sum(); DoubleArray(t.size) { t[it] / gain } }

        /** The last [taps]' worth of input, a ring; [heard] counts every sample given. */
        private val recent = FloatArray(taps.size)
        private var heard = 0L

        /** The frame being filled with low-rate samples. */
        private val frame = FloatArray(n)
        private var filled = 0

        // Per frame: its strongest peaks' strength over the frame's maximum, their pitch in cents, whether each is
        // an off-grid harmonic, and that maximum.
        private val strengths = ArrayList<FloatArray>()
        private val pitches = ArrayList<DoubleArray>()
        private val harmonic = ArrayList<BooleanArray>()
        private val frameMax = ArrayList<Double>()

        /** Adds the first [length] of [samples]: mono, -1 to 1. */
        fun add(samples: FloatArray, length: Int = samples.size) {
            for (i in 0 until length) {
                if (step == 1) { push(samples[i]); continue }
                recent[(heard % recent.size).toInt()] = samples[i]
                heard++
                // An output for every [step]th input, once the filter has that sample at its centre.
                if (heard >= recent.size && (heard - 1 - half) % step == 0L) {
                    var v = 0.0
                    var at = (heard % recent.size).toInt() // the oldest
                    for (t in taps.indices) {
                        v += recent[at] * taps[t]
                        if (++at == recent.size) at = 0
                    }
                    push(v.toFloat())
                }
            }
        }

        private fun push(sample: Float) {
            frame[filled++] = sample
            if (filled < n) return
            analyse()
            System.arraycopy(frame, hop, frame, 0, n - hop)
            filled = n - hop
        }

        private fun analyse() {
            for (i in 0 until n) { re[i] = frame[i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            var top = 0.0
            for (k in lowBin - 1..highBin + 1) {
                mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
                if (k in lowBin..highBin && mag[k] > top) top = mag[k]
            }
            val peaks = ArrayList<DoubleArray>()
            if (top > 0.0) {
                for (k in lowBin..highBin) {
                    val b = mag[k]
                    if (b < top * PEAK_FLOOR || b <= mag[k - 1] || b < mag[k + 1]) continue
                    // Where the peak really is, between bins: a parabola through the log magnitudes.
                    val la = ln(mag[k - 1] + 1e-12); val lb = ln(b + 1e-12); val lc = ln(mag[k + 1] + 1e-12)
                    val denom = la - 2 * lb + lc
                    val delta = if (denom == 0.0) 0.0 else (0.5 * (la - lc) / denom).coerceIn(-0.5, 0.5)
                    peaks += doubleArrayOf((k + delta) * rate / n, b)
                }
            }
            val strongest = peaks.sortedByDescending { it[1] }.take(PEAKS_PER_FRAME)
            strengths += FloatArray(strongest.size) { (strongest[it][1] / top).toFloat() }
            pitches += DoubleArray(strongest.size) { 1200.0 * ln(strongest[it][0] / 440.0) / ln(2.0) }
            harmonic += BooleanArray(strongest.size) { isOffGridHarmonic(strongest[it], strongest) }
            frameMax += top
        }

        /** What the audio heard so far reads as. */
        fun result(): Result {
            val none = Result(0.0, 0.0, 0)
            val loudest = frameMax.maxOrNull() ?: return none
            if (loudest <= 0.0) return none

            val histogram = DoubleArray(BINS)
            var peakCount = 0
            var steadyFrames = 0
            for (f in STEADY_FRAMES until pitches.size - STEADY_FRAMES) {
                // A frame far quieter than the song's loudest is a gap or a tail: its peaks are mostly noise.
                if (frameMax[f] < loudest * 0.02) continue
                var counted = false
                for ((i, cents) in pitches[f].withIndex()) {
                    if (harmonic[f][i] || !isSteady(cents, f, pitches)) continue
                    val offset = cents - 100.0 * floor(cents / 100.0 + 0.5) // -50 until +50
                    val bin = floor((offset + 50.0) * BINS_PER_CENT).toInt().coerceIn(0, BINS - 1)
                    histogram[bin] += sqrt(strengths[f][i].toDouble())
                    peakCount++
                    counted = true
                }
                if (counted) steadyFrames++
            }
            if (peakCount == 0) return none
            return read(histogram, peakCount, steadyFrames)
        }
    }

    /** The histogram's peak, and how far it stands above the rest. */
    private fun read(histogram: DoubleArray, peakCount: Int, steadyFrames: Int): Result {
        // The smoothed histogram, round the circle, and its peak.
        val sigma = SMOOTH_CENTS * BINS_PER_CENT
        val reach = (3 * sigma).toInt()
        val kernel = DoubleArray(2 * reach + 1) { exp(-0.5 * ((it - reach) / sigma).pow(2)) }
        val smooth = DoubleArray(BINS) { b ->
            var v = 0.0
            for (j in -reach..reach) v += histogram[Math.floorMod(b + j, BINS)] * kernel[j + reach]
            v
        }
        var best = 0
        for (b in 1 until BINS) if (smooth[b] > smooth[best]) best = b

        // Refined: the weighted mean of the raw histogram around that peak.
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

        // How far the peak stands above the rest, and whether another rivals it.
        val median = smooth.sorted()[BINS / 2]
        val ratio = if (median > 0) smooth[best] / median else Double.MAX_VALUE
        val standing = ((ratio - RATIO_NONE) / (RATIO_FULL - RATIO_NONE)).coerceIn(0.0, 1.0)
        val away = (RIVAL_CENTS * BINS_PER_CENT).toInt()
        var rival = 0.0
        for (j in away..BINS - away) rival = maxOf(rival, smooth[Math.floorMod(best + j, BINS)])
        val alone = ((RIVAL_FULL - rival / smooth[best]) / (RIVAL_FULL - RIVAL_NONE)).coerceIn(0.0, 1.0)
        val enough = (steadyFrames.toDouble() / ENOUGH_FRAMES).coerceAtMost(1.0)
        return Result(cents, standing * alone * enough, peakCount)
    }

    /** Whether a peak at [cents] in frame [f] holds its pitch through the frames either side. */
    private fun isSteady(cents: Double, f: Int, pitches: List<DoubleArray>): Boolean {
        for (d in 1..STEADY_FRAMES) {
            if (pitches[f - d].none { abs(it - cents) < STEADY_CENTS }) return false
            if (pitches[f + d].none { abs(it - cents) < STEADY_CENTS }) return false
        }
        return true
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
