package com.stash.core.media.equalizer.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tuning's real engine (Signalsmith Stretch, through JNI) on a phone: retuning to A = 432 Hz moves
 * every partial by exactly 432/440 at the same speed, at 44.1 and 96 kHz.
 *
 * A synthesized chord (an A at 440 Hz with harmonics at 880 and 1320, and a low E at 82.41) goes
 * through the stage in the output stage's own chunks of 2048 frames, followed by the stage's
 * latency of silence (how the output stage drains it at end of stream). Then:
 *  - an FFT finds every peak at hz x 432/440 within 0.5 Hz;
 *  - a one-second tone burst comes out one second long, delayed by the latency the stage reports.
 *
 * Run: ./gradlew :core:media:connectedDebugAndroidTest (on a phone).
 */
@RunWith(AndroidJUnit4::class)
class SignalsmithPitchStageTest {

    private val factor = 432f / 440f
    private val partials = doubleArrayOf(82.41, 440.0, 880.0, 1320.0)

    @Test fun engineLoadsOnThisPhone() {
        assertWithMessage("libstash_tuning.so did not load").that(SignalsmithPitchStage.isAvailable).isTrue()
    }

    @Test fun retunesEveryPartialTo432At44k() = checkPeaks(44_100)

    @Test fun retunesEveryPartialTo432At96k() = checkPeaks(96_000)

    @Test fun keepsLengthAndReportsItsLatencyAt44k() = checkTiming(44_100)

    @Test fun keepsLengthAndReportsItsLatencyAt96k() = checkTiming(96_000)

    private fun checkPeaks(rate: Int) {
        val seconds = 4.0
        val n = (rate * seconds).toInt()
        val input = FloatArray(n * CH)
        for (f in 0 until n) {
            var v = 0.0
            for (p in partials) v += 0.2 * sin(2 * PI * p * f / rate)
            input[f * CH] = v.toFloat()
            input[f * CH + 1] = v.toFloat()
        }
        val (out, latency) = run(rate, input, n)
        assertWithMessage("frames out vs in + latency").that(out.size / CH).isEqualTo(n + latency)

        // A clean stretch from 0.5 s after the stage filled up, a power of two long.
        val segment = if (rate > 48_000) 1 shl 18 else 1 shl 17
        val start = latency + rate / 2
        check(start + segment <= n + latency) { "test signal too short" }
        val spectrum = magnitudeSpectrum(out, start, segment, padTo = segment * 4)
        val binHz = rate.toDouble() / (segment * 4)
        for (p in partials) {
            val want = p * factor
            val got = peakNear(spectrum, want, binHz, searchHz = 3.0)
            assertWithMessage("$p Hz at $rate Hz should come out at ${"%.2f".format(java.util.Locale.ROOT, want)} Hz")
                .that(got).isWithin(0.5).of(want)
        }
    }

    private fun checkTiming(rate: Int) {
        val n = rate * 3
        val input = FloatArray(n * CH)
        val on = rate // burst from 1.0 s ...
        val off = 2 * rate // ... to 2.0 s
        val edge = rate / 100 // 10 ms raised-cosine edges, no clicks
        for (f in on until off) {
            val ramp = when {
                f - on < edge -> 0.5 - 0.5 * cos(PI * (f - on) / edge)
                off - f < edge -> 0.5 - 0.5 * cos(PI * (off - f) / edge)
                else -> 1.0
            }
            val v = (0.3 * ramp * sin(2 * PI * 440.0 * f / rate)).toFloat()
            input[f * CH] = v
            input[f * CH + 1] = v
        }
        val (out, latency) = run(rate, input, n)
        assertWithMessage("frames out vs in + latency").that(out.size / CH).isEqualTo(n + latency)
        // The output with the stage's fill-up cut off lines up with the input.
        val aligned = out.copyOfRange(latency * CH, out.size)
        val tolerance = rate / 100 // 10 ms

        val delay = centroid(out) - centroid(input)
        assertWithMessage("burst delay (frames) vs reported latency $latency").that(delay)
            .isWithin(tolerance.toDouble()).of(latency.toDouble())

        val inSpan = span(input, rate)
        val outSpan = span(aligned, rate)
        assertWithMessage("burst length out (frames) vs in ($inSpan)").that(outSpan.toDouble())
            .isWithin(2.0 * tolerance).of(inSpan.toDouble())
        val (inStart, _) = edges(input, rate)
        val (outStart, _) = edges(aligned, rate)
        assertWithMessage("burst onset after removing the reported latency").that(outStart.toDouble())
            .isWithin(2.0 * tolerance).of(inStart.toDouble())
    }

    /** Runs [input] ([n] stereo frames) through a fresh stage at 432, then drains it with silence. */
    private fun run(rate: Int, input: FloatArray, n: Int): Pair<FloatArray, Int> {
        val stage = SignalsmithPitchStage.create(rate, CH)
        assertWithMessage("stage for $rate Hz").that(stage).isNotNull()
        stage!!
        try {
            stage.setFactor(factor)
            val latency = stage.latencyFrames
            assertThat(latency).isGreaterThan(0)
            val total = n + latency
            val out = FloatArray(total * CH)
            System.arraycopy(input, 0, out, 0, input.size) // the tail region stays silent
            val chunk = FloatArray(CHUNK * CH)
            var f = 0
            while (f < total) {
                val frames = minOf(CHUNK, total - f)
                System.arraycopy(out, f * CH, chunk, 0, frames * CH)
                stage.process(chunk, frames)
                System.arraycopy(chunk, 0, out, f * CH, frames * CH)
                f += frames
            }
            return out to latency
        } finally {
            stage.release()
        }
    }

    /** Energy-weighted mean frame index of channel 0. */
    private fun centroid(s: FloatArray): Double {
        var e = 0.0
        var m = 0.0
        for (f in 0 until s.size / CH) {
            val v = s[f * CH].toDouble()
            e += v * v
            m += v * v * f
        }
        return m / e
    }

    /** First and last frame where the 10 ms RMS envelope of channel 0 is over half its peak. */
    private fun edges(s: FloatArray, rate: Int): Pair<Int, Int> {
        val win = rate / 100
        val frames = s.size / CH
        val env = DoubleArray(frames)
        var acc = 0.0
        for (f in 0 until frames) {
            val v = s[f * CH].toDouble()
            acc += v * v
            if (f >= win) {
                val o = s[(f - win) * CH].toDouble()
                acc -= o * o
            }
            env[f] = sqrt(maxOf(acc, 0.0) / win)
        }
        val half = env.max() / 2
        val first = env.indexOfFirst { it > half }
        val last = env.indexOfLast { it > half }
        return first to last
    }

    private fun span(s: FloatArray, rate: Int): Int = edges(s, rate).let { (a, b) -> b - a }

    /** |FFT| of a Hann-windowed stretch of channel 0, zero-padded to [padTo] (a power of two). */
    private fun magnitudeSpectrum(s: FloatArray, start: Int, length: Int, padTo: Int): DoubleArray {
        val re = DoubleArray(padTo)
        val im = DoubleArray(padTo)
        for (i in 0 until length) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (length - 1))
            re[i] = s[(start + i) * CH] * w
        }
        fft(re, im)
        return DoubleArray(padTo / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    /** The strongest peak within [searchHz] of [hz], refined by a parabola through log magnitudes. */
    private fun peakNear(mag: DoubleArray, hz: Double, binHz: Double, searchHz: Double): Double {
        val lo = ((hz - searchHz) / binHz).toInt().coerceAtLeast(1)
        val hi = ((hz + searchHz) / binHz).toInt().coerceAtMost(mag.size - 2)
        var k = lo
        for (i in lo..hi) if (mag[i] > mag[k]) k = i
        val a = ln(mag[k - 1] + 1e-30)
        val b = ln(mag[k] + 1e-30)
        val c = ln(mag[k + 1] + 1e-30)
        val denom = a - 2 * b + c
        val offset = if (abs(denom) < 1e-12) 0.0 else 0.5 * (a - c) / denom
        return (k + offset) * binHz
    }

    /** In-place iterative radix-2 FFT; [re].size must be a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private companion object {
        const val CH = 2
        /** The output stage's chunk size (ConvertingWriter.DEFAULT_CHUNK_FRAMES). */
        const val CHUNK = 2048
    }
}
