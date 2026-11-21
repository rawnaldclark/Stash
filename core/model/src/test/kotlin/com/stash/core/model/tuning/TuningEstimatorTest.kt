package com.stash.core.model.tuning

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The estimator against made audio whose tuning is known exactly (true tuning spec §5). Each case prints its
 * line, so a run is also the accuracy report.
 */
class TuningEstimatorTest {

    private val rate = 44_100
    private val tunings = doubleArrayOf(428.0, 432.0, 436.0, 438.0, 440.0, 442.0, 444.5, 452.0)

    private fun centsOf(hz: Double) = 1200.0 * ln(hz / 440.0) / ln(2.0)

    /** The distance between two offsets round the 100-cent circle. */
    private fun apart(a: Double, b: Double): Double {
        val d = abs(a - b) % 100.0
        return if (d > 50.0) 100.0 - d else d
    }

    private fun check(name: String, a4: Double, audio: FloatArray, within: Double) {
        val r = TuningEstimator.estimate(audio, rate)
        val error = apart(r.cents, centsOf(a4))
        println("REPORT %-34s A=%6.1f  read %6.2f Hz  error %5.2f c  confidence %.2f  peaks %d".format(name, a4, r.hz, error, r.confidence, r.peaks))
        assertWithMessage("$name at $a4: error in cents").that(error).isLessThan(within)
        assertWithMessage("$name at $a4: confidence").that(r.confidence).isAtLeast(TuningEstimator.MIN_CONFIDENCE)
    }

    @Test fun `a plain tone reads within 2 cents at every tuning`() {
        for (a4 in tunings) check("tone", a4, Synth(rate, a4, seed = 1).tone(seconds = 8.0), 2.0)
    }

    @Test fun `a song of chords, bass and melody reads within 2 cents at every tuning`() {
        for (a4 in tunings) check("song", a4, Synth(rate, a4, seed = 2).song(seconds = 24.0), 2.0)
    }

    @Test fun `drums and noise on top keep it within 5 cents`() {
        for (a4 in tunings) {
            val s = Synth(rate, a4, seed = 3)
            check("song + drums + noise", a4, s.mix(s.song(24.0), s.drums(24.0), 0.8f, s.noise(24.0), 0.05f), 5.0)
        }
    }

    @Test fun `a melody with wide vibrato over steady chords keeps it within 5 cents`() {
        for (a4 in tunings) check("song, melody with vibrato", a4, Synth(rate, a4, seed = 4, vibratoCents = 25.0).song(24.0), 5.0)
    }

    @Test fun `fed in small blocks it reads the same as all at once`() {
        val audio = Synth(rate, 436.0, seed = 12).song(12.0)
        val whole = TuningEstimator.estimate(audio, rate)
        val meter = TuningEstimator.Meter(rate)
        var at = 0
        val block = FloatArray(1_000)
        while (at < audio.size) {
            val count = minOf(block.size, audio.size - at)
            audio.copyInto(block, 0, at, at + count)
            meter.add(block, count)
            at += count
        }
        assertThat(meter.result()).isEqualTo(whole)
    }

    @Test fun `a meter can be read while audio is still arriving`() {
        val audio = Synth(rate, 444.5, seed = 13).song(20.0)
        val meter = TuningEstimator.Meter(rate)
        assertThat(meter.result().measured).isFalse()
        meter.add(audio, rate * 10)
        val early = meter.result()
        meter.add(audio.copyOfRange(rate * 10, audio.size))
        val late = meter.result()
        assertThat(early.measured).isTrue()
        assertThat(apart(early.cents, centsOf(444.5))).isLessThan(2.0)
        assertThat(apart(late.cents, centsOf(444.5))).isLessThan(2.0)
        assertThat(late.peaks).isGreaterThan(early.peaks)
    }

    @Test fun `stretched piano-like partials keep it within 5 cents`() {
        for (a4 in tunings) check("song, inharmonic", a4, Synth(rate, a4, seed = 5, inharmonicity = 0.0004).song(24.0), 5.0)
    }

    @Test fun `echo keeps it within 5 cents`() {
        for (a4 in tunings) {
            val s = Synth(rate, a4, seed = 6)
            check("song with echo", a4, s.echo(s.song(24.0)), 5.0)
        }
    }

    @Test fun `48 kHz and 96 kHz read the same`() {
        for (r in intArrayOf(48_000, 96_000)) {
            val audio = Synth(r, 432.0, seed = 7).song(16.0)
            val got = TuningEstimator.estimate(audio, r)
            println("REPORT song at %d Hz sample rate: read %.2f Hz, confidence %.2f".format(r, got.hz, got.confidence))
            assertThat(apart(got.cents, centsOf(432.0))).isLessThan(2.0)
            assertThat(got.measured).isTrue()
        }
    }

    @Test fun `a semitone down reads as on the grid`() {
        val r = TuningEstimator.estimate(Synth(rate, 415.3047, seed = 8).song(16.0), rate)
        println("REPORT song at 415.3: read %.2f Hz".format(r.hz))
        assertThat(abs(r.hz - 440.0)).isLessThan(0.5)
    }

    @Test fun `unpitched audio is not measured`() {
        val s = Synth(rate, 440.0, seed = 9)
        val cases = mapOf(
            "noise" to s.noise(20.0),
            "drums" to s.drums(20.0),
            "drums + noise" to s.mix(s.drums(20.0), s.noise(20.0), 0.3f),
            "pitch sliding all the way" to s.glide(20.0),
            "notes off any grid" to s.offGrid(20.0),
        )
        for ((name, audio) in cases) {
            val r = TuningEstimator.estimate(audio, rate)
            println("REPORT unpitched: %-28s confidence %.2f (read %.2f Hz, peaks %d)".format(name, r.confidence, r.hz, r.peaks))
            assertWithMessage(name).that(r.measured).isFalse()
        }
    }

    @Test fun `silence and a clip too short are not measured`() {
        assertThat(TuningEstimator.estimate(FloatArray(rate * 5), rate).confidence).isEqualTo(0.0)
        assertThat(TuningEstimator.estimate(FloatArray(100) { 0.5f }, rate).confidence).isEqualTo(0.0)
        // Two seconds of a real song: something is read, but too little to trust.
        val short = TuningEstimator.estimate(Synth(rate, 432.0, seed = 10).song(1.0), rate)
        assertThat(short.measured).isFalse()
    }

    @Test fun `the offset stays in range and hz follows it`() {
        val r = TuningEstimator.Result(-31.77, 1.0, 1000)
        assertThat(abs(r.hz - 432.0)).isLessThan(0.01)
        for (a4 in doubleArrayOf(427.6, 452.8)) {
            val got = TuningEstimator.estimate(Synth(rate, a4, seed = 11).song(12.0), rate)
            assertThat(got.cents).isAtLeast(-50.0)
            assertThat(got.cents).isLessThan(50.0)
            assertThat(apart(got.cents, centsOf(a4))).isLessThan(2.0)
        }
    }
}

/** Made audio on an equal-tempered grid at [a4]; [vibratoCents] is the melody's, as a singer's would be. */
private class Synth(
    private val rate: Int,
    private val a4: Double,
    seed: Int,
    private val vibratoCents: Double = 0.0,
    private val inharmonicity: Double = 0.0,
) {
    private val random = Random(seed)

    private fun midi(note: Int) = a4 * 2.0.pow((note - 69) / 12.0)

    private fun buffer(seconds: Double) = FloatArray((seconds * rate).toInt())

    /** One note with [partials] harmonics, a soft attack and a decay, added into [out] from [at] seconds. */
    private fun note(
        out: FloatArray, hz: Double, at: Double, length: Double, gain: Double,
        partials: Int = 6, decay: Double = 1.5, vibratoCents: Double = 0.0,
    ) {
        val from = (at * rate).toInt()
        val count = (length * rate).toInt()
        val vibratoRate = 5.0 + random.nextDouble()
        val phases = DoubleArray(partials) { random.nextDouble() * 2 * PI }
        val running = DoubleArray(partials)
        for (i in 0 until count) {
            val idx = from + i
            if (idx >= out.size) break
            val t = i.toDouble() / rate
            val bend = if (vibratoCents == 0.0) 1.0 else 2.0.pow(vibratoCents * sin(2 * PI * vibratoRate * t) / 1200.0)
            val env = minOf(1.0, t / 0.02) * exp(-decay * t / length) * minOf(1.0, (length - t) / 0.03)
            var v = 0.0
            for (h in 1..partials) {
                val f = hz * h * kotlin.math.sqrt(1 + inharmonicity * h * h) * bend
                if (f > rate / 2.2) break
                running[h - 1] += 2 * PI * f / rate
                v += sin(running[h - 1] + phases[h - 1]) / h.toDouble().pow(1.2)
            }
            out[idx] += (v * env * gain).toFloat()
        }
    }

    fun tone(seconds: Double) = buffer(seconds).also { note(it, midi(69), 0.0, seconds, 0.5, partials = 1, decay = 0.0) }

    /** Four chords round and round, a bass under them and a melody over, in a random major key. */
    fun song(seconds: Double): FloatArray {
        val out = buffer(seconds)
        val key = 48 + random.nextInt(12)
        val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val degrees = intArrayOf(0, 5, 3, 4)
        val beat = 0.5
        var t = 0.0
        var bar = 0
        while (t < seconds) {
            val root = scale[degrees[bar % 4]]
            val d = degrees[bar % 4]
            val chord = intArrayOf(root, scale[(d + 2) % 7] + if (d + 2 >= 7) 12 else 0, scale[(d + 4) % 7] + if (d + 4 >= 7) 12 else 0)
            for (c in chord) note(out, midi(key + 12 + c), t, 4 * beat, 0.12)
            for (b in 0 until 4) note(out, midi(key - 12 + root), t + b * beat, beat * 0.9, 0.2, partials = 4)
            for (b in 0 until 8) {
                if (random.nextInt(4) == 0) continue
                note(out, midi(key + 24 + scale[random.nextInt(7)]), t + b * beat / 2, beat / 2, 0.1, partials = 5, vibratoCents = vibratoCents)
            }
            t += 4 * beat
            bar++
        }
        return out
    }

    fun noise(seconds: Double) = FloatArray((seconds * rate).toInt()) { (random.nextFloat() * 2 - 1) * 0.5f }

    /** A kick (a falling thump), a snare and hats (bursts of noise). */
    fun drums(seconds: Double): FloatArray {
        val out = buffer(seconds)
        var t = 0.0
        var step = 0
        while (t < seconds) {
            val from = (t * rate).toInt()
            if (step % 4 == 0) {
                var phase = 0.0
                for (i in 0 until (0.15 * rate).toInt()) {
                    if (from + i >= out.size) break
                    val s = i.toDouble() / rate
                    phase += 2 * PI * (50 + 130 * exp(-s * 35)) / rate
                    out[from + i] += (sin(phase) * exp(-s * 22) * 0.6).toFloat()
                }
            }
            val len = if (step % 4 == 2) 0.12 else 0.03
            val gain = if (step % 4 == 2) 0.4 else 0.15
            for (i in 0 until (len * rate).toInt()) {
                if (from + i >= out.size) break
                out[from + i] += ((random.nextFloat() * 2 - 1) * gain * exp(-i.toDouble() / rate * (3 / len))).toFloat()
            }
            t += 0.25
            step++
        }
        return out
    }

    /** One voice sliding up two octaves and back, never resting on a note. */
    fun glide(seconds: Double): FloatArray {
        val out = buffer(seconds)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val f = 220.0 * 2.0.pow(1 + sin(2 * PI * t / 7.3))
            phase += 2 * PI * f / rate
            out[i] = (0.4 * sin(phase)).toFloat()
        }
        return out
    }

    /** Notes at pitches drawn evenly, with no grid behind them. */
    fun offGrid(seconds: Double): FloatArray {
        val out = buffer(seconds)
        var t = 0.0
        while (t < seconds) {
            note(out, 150.0 * 2.0.pow(random.nextDouble() * 3), t, 0.4, 0.3, partials = 3)
            t += 0.25
        }
        return out
    }

    fun mix(a: FloatArray, b: FloatArray, bGain: Float, c: FloatArray? = null, cGain: Float = 0f) =
        FloatArray(a.size) { a[it] + b[it] * bGain + (c?.get(it) ?: 0f) * cGain }

    fun echo(a: FloatArray): FloatArray {
        val out = a.copyOf()
        for ((delay, gain) in listOf(0.031 to 0.5f, 0.077 to 0.35f, 0.213 to 0.25f)) {
            val d = (delay * rate).toInt()
            for (i in d until out.size) out[i] += a[i - d] * gain
        }
        return out
    }
}
