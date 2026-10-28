package com.stash.core.media.equalizer.dsp

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.LoudnessState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

class FloatDspChainTest {

    private var eq = EqState()
    private var loudness = LoudnessState(enabled = true, currentTrackGainDb = 0f)
    private val chain = FloatDspChain({ eq }, { loudness })

    private fun sine(freq: Double, sr: Int, frames: Int, ch: Int, amp: Float = 0.25f): FloatArray {
        val out = FloatArray(frames * ch)
        for (f in 0 until frames) {
            val v = (amp * sin(2 * PI * freq * f / sr)).toFloat()
            for (c in 0 until ch) out[f * ch + c] = v
        }
        return out
    }

    private fun rms(s: FloatArray, from: Int = 0): Double {
        var acc = 0.0
        for (i in from until s.size) acc += s[i].toDouble() * s[i]
        return sqrt(acc / (s.size - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    @Test fun `defaults are inactive - loudness on but unmeasured touches nothing`() {
        chain.configure(44_100, 2)
        assertThat(dspActivation(eq, loudness).any).isFalse()
        assertThat(chain.isActive()).isFalse()
    }

    @Test fun `eq enabled but flat stays inactive`() {
        eq = EqState(enabled = true)
        chain.configure(48_000, 2)
        assertThat(chain.isActive()).isFalse()
    }

    @Test fun `a measured track activates loudness and the limiter`() {
        loudness = loudness.copy(currentTrackGainDb = -6f)
        val act = dspActivation(eq, loudness)
        assertThat(act.loudness).isTrue()
        assertThat(act.limiter).isTrue()
    }

    @Test fun `plus 6 dB band at its centre frequency lifts a sine by about 6 dB`() {
        eq = EqState(enabled = true, gainsDb = floatArrayOf(6f, 0f, 0f, 0f, 0f))
        val sr = 96_000
        chain.configure(sr, 2)
        val input = sine(60.0, sr, sr, 2, amp = 0.1f)
        val out = input.copyOf()
        chain.process(out, sr)
        // Measure the second half (sample indices sr..2sr), after the filter has settled.
        val settled = db(rms(out, from = sr) / rms(input, from = sr))
        assertThat(settled).isWithin(0.15).of(6.0)
    }

    @Test fun `double precision filters stay accurate at 60 Hz on 192 kHz`() {
        // Poles hug the unit circle here; a float biquad loses ~0.5 dB and adds noise.
        eq = EqState(enabled = true, gainsDb = floatArrayOf(-9f, 0f, 0f, 0f, 0f))
        val sr = 192_000
        chain.configure(sr, 1)
        val frames = sr * 2
        val input = sine(60.0, sr, frames, 1, amp = 0.5f)
        val out = input.copyOf()
        chain.process(out, frames)
        val half = frames / 2
        val settled = db(rms(out.copyOfRange(half, frames)) / rms(input.copyOfRange(half, frames)))
        assertThat(settled).isWithin(0.1).of(-9.0)
    }

    @Test fun `bands above Nyquist are skipped instead of going unstable`() {
        eq = EqState(enabled = true, gainsDb = floatArrayOf(0f, 0f, 0f, 0f, 12f))
        val sr = 22_050 // 14 kHz is above Nyquist
        chain.configure(sr, 1)
        val input = sine(1000.0, sr, sr, 1)
        val out = input.copyOf()
        chain.process(out, sr)
        assertThat(out.all { it.isFinite() }).isTrue()
        assertThat(db(rms(out) / rms(input))).isWithin(0.01).of(0.0)
    }

    @Test fun `bass shelf boosts lows and leaves highs`() {
        eq = EqState(enabled = true, bassBoostDb = 9f)
        val sr = 48_000
        chain.configure(sr, 2)
        val low = sine(30.0, sr, sr * 2, 2, amp = 0.05f)
        val lowOut = low.copyOf().also { chain.process(it, sr * 2) }
        chain.reset()
        val high = sine(8000.0, sr, sr, 2, amp = 0.05f)
        val highOut = high.copyOf().also { chain.process(it, sr) }
        val lowGain = db(rms(lowOut.copyOfRange(sr, lowOut.size)) / rms(low.copyOfRange(sr, low.size)))
        assertThat(lowGain).isWithin(0.5).of(9.0)
        assertThat(db(rms(highOut) / rms(high))).isWithin(0.2).of(0.0)
    }

    @Test fun `AutoMix low shelf removes one deck's bass and leaves highs`() {
        var shelfDb = -20f
        val autoMixChain = FloatDspChain({ EqState() }, { loudness }, { shelfDb })
        val sr = 48_000
        autoMixChain.configure(sr, 2)
        assertThat(autoMixChain.isActive()).isTrue()

        val low = sine(40.0, sr, sr * 2, 2, amp = 0.20f)
        val lowOut = low.copyOf().also { autoMixChain.process(it, sr * 2) }
        val lowGain = db(
            rms(lowOut.copyOfRange(sr * 2, lowOut.size)) /
                rms(low.copyOfRange(sr * 2, low.size)),
        )
        assertThat(lowGain).isLessThan(-15.0)

        autoMixChain.reset()
        val high = sine(8_000.0, sr, sr, 2, amp = 0.20f)
        val highOut = high.copyOf().also { autoMixChain.process(it, sr) }
        assertThat(db(rms(highOut) / rms(high))).isWithin(0.25).of(0.0)

        shelfDb = 0f
        val settle = sine(40.0, sr, sr, 2, amp = 0.20f)
        autoMixChain.process(settle, sr)
        assertThat(autoMixChain.isActive()).isFalse()
    }

    @Test fun `preamp ramps instead of stepping, then holds exactly`() {
        eq = EqState(enabled = true, preampDb = -6.0206f) // x0.5
        val sr = 48_000
        chain.configure(sr, 2)
        chain.reset() // snaps to the target
        val dc = FloatArray(2 * 100) { 0.8f }
        chain.process(dc, 100)
        assertThat(dc[0]).isWithin(1e-4f).of(0.4f)
        // Now move it: the change must glide over ~15 ms, not jump.
        eq = eq.copy(preampDb = 0f)
        val frames = sr / 20
        val ramp = FloatArray(2 * frames) { 0.4f }
        chain.process(ramp, frames)
        var maxStep = 0f
        for (f in 1 until frames) maxStep = max(maxStep, abs(ramp[f * 2] - ramp[(f - 1) * 2]))
        assertThat(maxStep).isLessThan(0.01f)
        // Ramp finished: preamp is back to unity, so 0.4 in → 0.4 out.
        assertThat(ramp[2 * (frames - 1)]).isWithin(1e-4f).of(0.4f)
        assertThat(ramp[0]).isWithin(1e-3f).of(0.2f) // started from x0.5
        // left and right always share a frame's gain
        for (f in 0 until frames) assertThat(ramp[f * 2]).isEqualTo(ramp[f * 2 + 1])
    }

    @Test fun `limiter never lets output reach full scale, even at plus 15 dB`() {
        eq = EqState(enabled = true, bassBoostDb = 15f, gainsDb = floatArrayOf(12f, 12f, 0f, 0f, 0f))
        val sr = 44_100
        chain.configure(sr, 2)
        val input = sine(60.0, sr, sr, 2, amp = 0.9f)
        chain.process(input, sr)
        val peak = input.maxOf { abs(it) }
        assertThat(peak).isLessThan(1f)
        assertThat(input.all { it.isFinite() }).isTrue()
    }

    @Test fun `limiter is the identity below threshold`() {
        val lim = SoftLimiter().apply { configure(48_000) }
        val s = sine(440.0, 48_000, 4800, 2, amp = 0.8f)
        val copy = s.copyOf()
        lim.process(s, 4800, 2)
        assertThat(s).isEqualTo(copy)
        assertThat(lim.isEngaged).isFalse()
    }

    @Test fun `soft clip is continuous at the knee and bounded`() {
        val t = SoftLimiter.THRESHOLD
        assertThat(SoftLimiter.softClip(t)).isEqualTo(t)
        assertThat(SoftLimiter.softClip(t + 1e-4f)).isWithin(2e-4f).of(t + 1e-4f)
        assertThat(SoftLimiter.softClip(50f)).isAtMost(SoftLimiter.CEILING)
        assertThat(SoftLimiter.softClip(-50f)).isAtLeast(-SoftLimiter.CEILING)
        assertThat(SoftLimiter.softClip(Float.MAX_VALUE)).isLessThan(1f)
    }

    @Test fun `no added latency - an impulse comes out on the same frame`() {
        loudness = loudness.copy(currentTrackGainDb = -6f)
        chain.configure(48_000, 1)
        val s = FloatArray(64).also { it[10] = 0.5f }
        chain.process(s, 64)
        val peakAt = s.indices.maxByOrNull { abs(s[it]) }
        assertThat(peakAt).isEqualTo(10)
    }

    @Test fun `non-finite input is silenced, not propagated`() {
        eq = EqState(enabled = true, preampDb = 3f)
        chain.configure(48_000, 2)
        val s = floatArrayOf(Float.NaN, 0.1f, Float.POSITIVE_INFINITY, 0.1f)
        chain.process(s, 2)
        assertThat(s.all { it.isFinite() }).isTrue()
    }

    @Test fun `biquad coefficients normalise and 0 dB is the identity`() {
        val bank = BiquadBank(1, 1)
        bank.setPeaking(0, 1000f, 0f, 1f, 48_000)
        assertThat(bank.coefficients(0).toList()).containsExactly(1.0, 0.0, 0.0, 0.0, 0.0).inOrder()
        val s = sine(440.0, 48_000, 256, 1)
        val copy = s.copyOf()
        bank.process(s, 256)
        assertThat(s).isEqualTo(copy)
    }
}
