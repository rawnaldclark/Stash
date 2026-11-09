package com.stash.core.media.equalizer.dsp

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.model.TuningMath
import org.junit.Test

/** Tuning's place in the chain: first, gated on the reference, with its delay owned by the caller. */
class FloatDspChainTuningTest {

    private var hz = 440f
    private val stages = mutableListOf<FixedDelayFakeStage>()
    private fun chain(factory: PitchStageFactory = PitchStageFactory { _, ch -> FixedDelayFakeStage(ch, 4).also { stages += it } }) =
        FloatDspChain({ EqState() }, { LoudnessState(enabled = true, currentTrackGainDb = 0f) }, { hz }, factory)

    @Test fun `the limiter rides with tuning, and 440 touches nothing`() {
        val flat = EqState()
        val unmeasured = LoudnessState(enabled = true, currentTrackGainDb = 0f)
        val standard = dspActivation(flat, unmeasured, tuning = TuningMath.isRetuned(440f))
        assertThat(standard.tuning).isFalse()
        assertThat(standard.limiter).isFalse()
        assertThat(standard.any).isFalse()

        val tuned = dspActivation(flat, unmeasured, tuning = TuningMath.isRetuned(432f))
        assertThat(tuned.tuning).isTrue()
        assertThat(tuned.limiter).isTrue()
        assertThat(tuned.any).isTrue()
    }

    @Test fun `at 440 tuning is neither wanted nor built`() {
        val c = chain()
        c.configure(44_100, 2)
        assertThat(c.tuningWanted()).isFalse()
        c.engageTuning(c.tuningWanted())
        assertThat(c.tuningEngaged).isFalse()
        assertThat(c.latencyFrames).isEqualTo(0)
        assertThat(c.isActive()).isFalse()
        assertThat(stages).isEmpty()
    }

    @Test fun `a non-standard reference engages the stage first in the chain, with its delay`() {
        hz = 432f
        val c = chain()
        c.configure(44_100, 2)
        assertThat(c.tuningWanted()).isTrue()
        c.engageTuning(true)
        assertThat(c.tuningEngaged).isTrue()
        assertThat(c.isActive()).isTrue()
        assertThat(c.latencyFrames).isEqualTo(4)
        assertThat(stages.single().factor.toDouble()).isWithin(1e-7).of(432.0 / 440.0)

        val samples = FloatArray(16) { 0.05f * (it + 1) } // 8 stereo frames, all under the limiter
        val input = samples.copyOf()
        c.process(samples, 8)
        // 4 frames of the stage filling up, then the input from its first frame.
        assertThat(samples.copyOfRange(0, 8).toList()).containsExactlyElementsIn(List(8) { 0f })
        for (i in 8 until 16) assertThat(samples[i]).isWithin(1e-6f).of(input[i - 8])
    }

    @Test fun `moving between non-standard references changes the factor live`() {
        hz = 432f
        val c = chain()
        c.configure(48_000, 2)
        c.engageTuning(true)
        hz = 444f
        c.process(FloatArray(4), 2)
        assertThat(stages.single().factor.toDouble()).isWithin(1e-7).of(444.0 / 440.0)
    }

    @Test fun `reset empties the stage (a seek)`() {
        hz = 432f
        val c = chain()
        c.configure(48_000, 2)
        c.engageTuning(true)
        val before = stages.single().resets
        c.reset()
        assertThat(stages.single().resets).isEqualTo(before + 1)
    }

    @Test fun `without an engine tuning stays out and stops asking`() {
        hz = 432f
        val c = chain(PitchStageFactory.NONE)
        c.configure(48_000, 2)
        assertThat(c.tuningWanted()).isTrue()
        c.engageTuning(true)
        assertThat(c.tuningEngaged).isFalse()
        assertThat(c.tuningWanted()).isFalse() // so the output stage doesn't rebuild over and over
        assertThat(c.isActive()).isFalse()
    }

    @Test fun `an engine that throws counts as none`() {
        hz = 432f
        val c = chain(PitchStageFactory { _, _ -> error("no memory") })
        c.configure(48_000, 2)
        c.engageTuning(true)
        assertThat(c.tuningEngaged).isFalse()
    }

    @Test fun `taking tuning out or releasing frees the engine`() {
        hz = 432f
        val c = chain()
        c.configure(48_000, 2)
        c.engageTuning(true)
        c.engageTuning(false)
        assertThat(stages.single().released).isTrue()
        assertThat(c.latencyFrames).isEqualTo(0)

        c.engageTuning(true)
        c.release()
        assertThat(stages.last().released).isTrue()
        assertThat(c.tuningEngaged).isFalse()
    }

    @Test fun `a new format drops the old stage`() {
        hz = 432f
        val c = chain()
        c.configure(44_100, 2)
        c.engageTuning(true)
        c.configure(96_000, 2)
        assertThat(stages.single().released).isTrue()
        assertThat(c.tuningEngaged).isFalse()
    }
}
