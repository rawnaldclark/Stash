package com.stash.core.media.equalizer.dsp

import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.model.TuningMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Which DSP stages change the signal for a given pref state.
 *
 * The single source of truth for "is the EQ doing anything": [FloatDspChain]
 * gates every stage on it, and the signal-path UI reads it to say whether the
 * output is untouched.
 */
data class DspActivation(
    val preamp: Boolean,
    val eq: Boolean,
    val bass: Boolean,
    val loudness: Boolean,
    /** Tuning's pitch stage is in the path (a reference other than 440 Hz). */
    val tuning: Boolean = false,
) {
    /**
     * The limiter guards upstream gain; it rides along with any active stage. With Tuning that
     * includes the small peak overshoot a phase vocoder can add.
     */
    val limiter: Boolean get() = preamp || eq || bass || loudness || tuning

    /** True when any stage would alter a single sample. */
    val any: Boolean get() = limiter
}

fun dspActivation(eq: EqState, loudness: LoudnessState, tuning: Boolean = false) = DspActivation(
    preamp = eq.enabled && eq.preampDb != 0f,
    eq = eq.enabled && eq.gainsDb.any { it != 0f },
    bass = eq.enabled && eq.bassBoostDb != 0f,
    // Normalization is "on" by default, but a track it hasn't measured (or one
    // already at the target) gets 0 dB — no reason to touch a single sample.
    loudness = loudness.enabled && loudness.currentTrackGainDb != 0f,
    tuning = tuning,
)

/**
 * Stash's equalizer, bass boost, loudness normalization and limiter as one
 * float chain, run by the output stage on interleaved float PCM in place.
 *
 * Replaces the old per-stage Media3 AudioProcessors, which could only work on
 * 16-bit audio (Media3 skips user processors on its high-resolution float
 * path) — so 24-bit FLAC was truncated to 16 bits before the EQ ever saw it,
 * and every one of the five stages then converted int16 → float → int16 again,
 * truncating (not rounding, not dithering) each time.
 *
 * Here the audio stays float from decoder to output, the filters run in double
 * precision (a float biquad at 60 Hz / 192 kHz has poles so close to the unit
 * circle that its rounding noise is audible), and when every stage is inactive
 * [process] is never called — the samples go to the output untouched.
 *
 * Every stage but Tuning is zero-latency (the old lookahead limiter silently
 * dropped the last 2 ms of every track and prefixed 2 ms of silence). Tuning's
 * pitch stage ([PitchStage]) keeps frames 1:1 but delays them by
 * [latencyFrames]; the output stage compensates the position for that and
 * drains the tail at end of stream. It is in the path only while
 * [tuningEngaged], decided at stream boundaries ([engageTuning]): at 440 Hz it
 * isn't there at all, so the samples pass untouched and it costs nothing.
 *
 * Order: Tuning → preamp → 5-band EQ → bass shelf → loudness gain → limiter.
 *
 * State is sampled once per [process] call through the suppliers (a volatile
 * StateFlow read), so a pref change lands within one chunk (~46 ms).
 * Not thread-safe: one instance per output, used on the playback thread.
 */
class FloatDspChain(
    private val eqState: () -> EqState,
    private val loudnessState: () -> LoudnessState,
    /** Tuning's reference (A4, Hz); 440 keeps the pitch stage out. */
    private val tuningHz: () -> Float = { TuningMath.STANDARD_HZ },
    /** Builds the pitch stage for a stream; [PitchStageFactory.NONE] where there is no engine. */
    private val pitchStages: PitchStageFactory = PitchStageFactory.NONE,
) {
    private var sampleRate = 0
    private var channels = 0

    private var tuner: PitchStage? = null
    private var tunerHz = TuningMath.STANDARD_HZ

    /** The engine couldn't be built for this stream: Tuning stays out of it. */
    private var tunerMissing = false

    /** Whether Tuning's pitch stage is in this stream's path. Changes only through [engageTuning]. */
    var tuningEngaged = false
        private set

    /** The pitch stage's delay while it is in the path, else 0. */
    val latencyFrames: Int get() = if (tuningEngaged) tuner?.latencyFrames ?: 0 else 0

    /**
     * The reference this stream is retuned to right now, or null when it isn't (no stage, or a
     * stage still in the path at 440 until its rebuild). What the signal path says.
     */
    val retunedToHz: Float? get() = tunerHz.takeIf { tuningEngaged && TuningMath.isRetuned(it) }

    private val preamp = GainRamp()
    private val loudness = GainRamp()
    private var eqBank = BiquadBank(BAND_FREQS.size, 1)
    private var bassBank = BiquadBank(1, 1)
    private val limiter = SoftLimiter()

    private var appliedGains = FloatArray(0)
    private var appliedBassDb = Float.NaN
    private var eqWasActive = false
    private var bassWasActive = false

    val isConfigured: Boolean get() = sampleRate > 0

    fun configure(sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels > 0) { "bad format $sampleRate Hz × $channels" }
        if (sampleRate != this.sampleRate || channels != this.channels) {
            // A stage is built for one format: drop it, and let engageTuning build the next.
            releaseTuner()
            tuningEngaged = false
            tunerMissing = false
        }
        this.sampleRate = sampleRate
        this.channels = channels
        eqBank = BiquadBank(BAND_FREQS.size, channels)
        bassBank = BiquadBank(1, channels)
        appliedGains = FloatArray(0)
        appliedBassDb = Float.NaN
        preamp.configure(sampleRate)
        loudness.configure(sampleRate)
        limiter.configure(sampleRate)
        reset()
    }

    /**
     * Clears filter and envelope state (seek, flush). Gains jump straight to
     * their targets: a ramp across a seek would just be a fade-in of new audio.
     */
    fun reset() {
        eqBank.reset()
        bassBank.reset()
        limiter.reset()
        if (tuningEngaged) tuner?.reset()
        val eq = eqState()
        val ld = loudnessState()
        preamp.snapTo(preampTarget(eq))
        loudness.snapTo(loudnessTarget(ld))
    }

    /** True when [process] would change the signal right now (cheap; per write). */
    fun isActive(): Boolean {
        if (!isConfigured) return false
        if (tuningEngaged) return true
        val act = dspActivation(eqState(), loudnessState())
        return act.any || preamp.isActive || loudness.isActive || limiter.isEngaged
    }

    /**
     * Whether Tuning wants the pitch stage in the path right now: a reference other than 440 Hz,
     * and an engine for this stream. The output stage compares it with [tuningEngaged] on each
     * write; a difference mid-stream means a rebuild (the delay can't change without a jump).
     */
    fun tuningWanted(): Boolean = isConfigured && !tunerMissing && TuningMath.isRetuned(tuningHz())

    /**
     * Puts the pitch stage in the path ([on]) or takes it out, empty either way. Only at a stream
     * boundary (before the first frame, or after a flush): the stage's delay moves the audio.
     */
    fun engageTuning(on: Boolean) {
        if (!on) {
            tuningEngaged = false
            releaseTuner()
            return
        }
        if (!isConfigured) return
        val stage = tuner ?: runCatching { pitchStages.create(sampleRate, channels) }.getOrNull()
        if (stage == null) {
            tunerMissing = true
            tuningEngaged = false
            return
        }
        tuner = stage
        stage.reset()
        tunerHz = TuningMath.sanitize(tuningHz())
        stage.setFactor(TuningMath.factor(tunerHz))
        tuningEngaged = true
    }

    /** Frees the pitch stage (the output was released). */
    fun release() {
        tuningEngaged = false
        releaseTuner()
    }

    private fun releaseTuner() {
        tuner?.release()
        tuner = null
    }

    /** Processes [frames] interleaved frames of [samples] in place. */
    fun process(samples: FloatArray, frames: Int) {
        if (!isConfigured || frames <= 0) return
        val eq = eqState()
        val ld = loudnessState()
        val act = dspActivation(eq, ld, tuning = tuningEngaged)

        // Tuning first, so the limiter at the end catches what it adds.
        val stage = tuner
        if (tuningEngaged && stage != null) {
            // 440 while still engaged (Tuning was just switched off): factor 1 until the rebuild.
            val hz = TuningMath.sanitize(tuningHz())
            if (hz != tunerHz) {
                stage.setFactor(TuningMath.factor(hz))
                tunerHz = hz
            }
            stage.process(samples, frames)
        }

        preamp.setTarget(preampTarget(eq))
        if (preamp.isActive) preamp.apply(samples, frames, channels)

        if (act.eq) {
            if (!eqWasActive) eqBank.reset()
            if (!eq.gainsDb.contentEquals(appliedGains)) {
                for (b in BAND_FREQS.indices) {
                    eqBank.setPeaking(b, BAND_FREQS[b], eq.gainsDb.getOrElse(b) { 0f }, BAND_Q, sampleRate)
                }
                appliedGains = eq.gainsDb.copyOf()
            }
            eqBank.process(samples, frames)
        }
        eqWasActive = act.eq

        if (act.bass) {
            if (!bassWasActive) bassBank.reset()
            if (eq.bassBoostDb != appliedBassDb) {
                bassBank.setLowShelf(0, SHELF_FREQ, eq.bassBoostDb, SHELF_Q, sampleRate)
                appliedBassDb = eq.bassBoostDb
            }
            bassBank.process(samples, frames)
        }
        bassWasActive = act.bass

        loudness.setTarget(loudnessTarget(ld))
        if (loudness.isActive) loudness.apply(samples, frames, channels)

        // The limiter stays in until its gain reduction has released, so
        // switching the boost stages off can't step the level.
        if (act.limiter || limiter.isEngaged) limiter.process(samples, frames, channels)
    }

    private fun preampTarget(eq: EqState): Float =
        if (eq.enabled && eq.preampDb != 0f) dbToLinear(eq.preampDb) else 1f

    private fun loudnessTarget(ld: LoudnessState): Float =
        if (ld.enabled && ld.currentTrackGainDb != 0f) dbToLinear(ld.currentTrackGainDb) else 1f

    companion object {
        val BAND_FREQS: FloatArray = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)
        const val BAND_Q = 1f
        const val SHELF_FREQ = 100f
        const val SHELF_Q = 0.7f

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    }
}

/**
 * A linear gain that glides to a new target over [RAMP_MS] instead of
 * stepping (a step in gain is a click). Ramps per frame, so both channels of
 * a frame always get the same gain — the old stage advanced per sample, which
 * skewed the stereo image during every ramp.
 */
internal class GainRamp {
    var current = 1f
        private set
    private var target = 1f
    private var step = 0f
    private var remaining = 0
    private var rampFrames = 1

    val isActive: Boolean get() = remaining > 0 || current != 1f

    fun configure(sampleRate: Int) {
        rampFrames = (sampleRate * RAMP_MS / 1000).coerceAtLeast(1)
    }

    fun snapTo(value: Float) {
        target = value
        current = value
        remaining = 0
        step = 0f
    }

    fun setTarget(value: Float) {
        if (value == target) return
        target = value
        remaining = rampFrames
        step = (target - current) / remaining
    }

    fun apply(samples: FloatArray, frames: Int, channels: Int) {
        var i = 0
        if (remaining == 0) {
            val g = current
            val n = frames * channels
            while (i < n) { samples[i] *= g; i++ }
            return
        }
        for (f in 0 until frames) {
            val g = current
            for (c in 0 until channels) { samples[i] *= g; i++ }
            if (remaining > 0) {
                remaining--
                current = if (remaining == 0) target else current + step
            }
        }
    }

    companion object {
        const val RAMP_MS = 15
    }
}

/**
 * [bands] cascaded biquads per channel, Direct Form II Transposed, in double
 * precision. Coefficients from the W3C Audio EQ Cookbook; shared across
 * channels, state per channel.
 */
internal class BiquadBank(private val bands: Int, private val channels: Int) {
    // b0, b1, b2, a1, a2 per band (a0 normalized to 1).
    private val coef = DoubleArray(bands * 5).also { c -> for (b in 0 until bands) c[b * 5] = 1.0 }
    private val state = DoubleArray(channels * bands * 2)

    fun reset() = state.fill(0.0)

    fun setPeaking(band: Int, freqHz: Float, gainDb: Float, q: Float, sampleRate: Int) {
        if (!inRange(freqHz, sampleRate) || gainDb == 0f) return setIdentity(band)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        set(band, 1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
    }

    fun setLowShelf(band: Int, freqHz: Float, gainDb: Float, q: Float, sampleRate: Int) {
        if (!inRange(freqHz, sampleRate) || gainDb == 0f) return setIdentity(band)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val k = 2 * sqrt(a) * alpha
        set(
            band,
            a * ((a + 1) - (a - 1) * cw + k),
            2 * a * ((a - 1) - (a + 1) * cw),
            a * ((a + 1) - (a - 1) * cw - k),
            (a + 1) + (a - 1) * cw + k,
            -2 * ((a - 1) + (a + 1) * cw),
            (a + 1) + (a - 1) * cw - k,
        )
    }

    fun process(samples: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                var x = samples[i].toDouble()
                var s = c * bands * 2
                var k = 0
                for (b in 0 until bands) {
                    val y = coef[k] * x + state[s]
                    state[s] = coef[k + 1] * x - coef[k + 3] * y + state[s + 1]
                    state[s + 1] = coef[k + 2] * x - coef[k + 4] * y
                    x = y
                    s += 2
                    k += 5
                }
                samples[i] = x.toFloat()
                i++
            }
        }
    }

    /** Test view of one band's normalized coefficients: b0, b1, b2, a1, a2. */
    internal fun coefficients(band: Int): DoubleArray = coef.copyOfRange(band * 5, band * 5 + 5)

    private fun set(band: Int, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        val k = band * 5
        coef[k] = b0 / a0
        coef[k + 1] = b1 / a0
        coef[k + 2] = b2 / a0
        coef[k + 3] = a1 / a0
        coef[k + 4] = a2 / a0
    }

    private fun setIdentity(band: Int) = set(band, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /**
     * A band at or above ~Nyquist has w0 ≥ π: the cookbook formula then yields
     * an unstable filter. The 14 kHz band on a 22.05 kHz stream is the real case.
     */
    private fun inRange(freqHz: Float, sampleRate: Int) = freqHz > 0f && freqHz < sampleRate * 0.45f
}

/**
 * Zero-latency peak limiter: a stereo-linked gain envelope (fast attack, slow
 * release) followed by a soft clipper whose ceiling is below 0 dBFS.
 *
 * The envelope does the work on sustained overs (a +12 dB bass boost);
 * the clipper rounds off the first few transient samples the envelope hasn't
 * caught yet — gently, from −1 dBFS up, and never past [CEILING]. Below the
 * threshold it is the identity, bit for bit.
 *
 * No lookahead means no delay line, so no audio is held back at end of stream
 * and no silence is inserted after a seek.
 */
internal class SoftLimiter {
    private var gain = 1f
    private var attack = 0f
    private var release = 0f

    /** True while gain reduction is still releasing. */
    val isEngaged: Boolean get() = gain < 1f - 1e-6f

    fun configure(sampleRate: Int) {
        attack = coeff(ATTACK_MS, sampleRate)
        release = coeff(RELEASE_MS, sampleRate)
        reset()
    }

    fun reset() { gain = 1f }

    fun process(samples: FloatArray, frames: Int, channels: Int) {
        var i = 0
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) {
                val v = samples[i + c]
                if (!v.isFinite()) samples[i + c] = 0f
                else { val a = abs(v); if (a > peak) peak = a }
            }
            val target = if (peak > THRESHOLD) THRESHOLD / peak else 1f
            gain += (target - gain) * (if (target < gain) attack else release)
            if (gain > 1f) gain = 1f
            for (c in 0 until channels) {
                samples[i] = softClip(samples[i] * gain)
                i++
            }
        }
    }

    private fun coeff(ms: Float, sampleRate: Int): Float =
        1f - exp(-1f / (sampleRate * ms / 1000f))

    companion object {
        /** −1 dBFS: where gain reduction and the soft knee begin. */
        const val THRESHOLD = 0.891f

        /**
         * The knee's asymptote, ≈ −0.18 dBFS. Strictly below full scale even
         * after float rounding (tanh saturates to exactly 1f for large inputs,
         * so an asymptote AT 1.0 would be reached), with a little margin for
         * inter-sample peaks in the DAC's reconstruction filter.
         */
        const val CEILING = 0.98f
        const val ATTACK_MS = 0.5f
        const val RELEASE_MS = 80f

        /**
         * Identity up to [THRESHOLD]; above it a tanh knee that meets the line
         * with slope 1 and approaches [CEILING].
         */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= THRESHOLD) return x
            val headroom = CEILING - THRESHOLD
            val y = THRESHOLD + headroom * tanh((a - THRESHOLD) / headroom)
            return if (x < 0f) -y else y
        }
    }
}
