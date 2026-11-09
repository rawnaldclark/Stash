package com.stash.core.media.output

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.HiResOutputMode
import com.stash.core.media.output.MixerDecision.Configure
import com.stash.core.media.output.MixerDecision.Reason
import com.stash.core.media.output.MixerDecision.Shared
import org.junit.Test

class MixerSelectionTest {

    private var idx = 0
    private fun opt(f: SampleFormat?, rate: Int, ch: Int = 2, bp: Boolean = false) =
        MixerOption(f, rate, ch, bp, idx++)

    /** A typical USB DAC on a phone that ships bit-perfect: 16/24/32-bit at 44.1–192 kHz. */
    private val dac = buildList {
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) {
            for (f in listOf(SampleFormat.INT16, SampleFormat.INT24, SampleFormat.INT32)) {
                add(opt(f, rate))
                add(opt(f, rate, bp = true))
            }
        }
    }

    @Test fun `standard mode never touches the mixer`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.STANDARD, 96_000, 2, 1f)
        assertThat(d).isEqualTo(Shared(Reason.MODE_STANDARD))
    }

    @Test fun `match rate picks the exact rate, widest format, and hands the mixer float`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.MATCH_RATE, 96_000, 2, 1f) as Configure
        assertThat(d.option.sampleRate).isEqualTo(96_000)
        assertThat(d.option.format).isEqualTo(SampleFormat.INT32)
        assertThat(d.bitPerfect).isFalse()
        assertThat(d.trackFormat).isEqualTo(SampleFormat.FLOAT)
    }

    @Test fun `bit-perfect writes exactly the mixer's integer format`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 44_100, 2, 1f) as Configure
        assertThat(d.bitPerfect).isTrue()
        assertThat(d.trackFormat).isEqualTo(d.option.format)
        assertThat(d.option.sampleRate).isEqualTo(44_100)
    }

    @Test fun `never picks a nearby rate - that would be resampling too`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.MATCH_RATE, 88_200, 2, 1f)
        assertThat(d).isEqualTo(Shared(Reason.RATE_UNSUPPORTED_BY_DAC))
    }

    @Test fun `bit-perfect falls back to rate match when the phone doesn't offer it`() {
        val noBp = dac.filterNot { it.bitPerfect }
        val d = MixerSelection.choose(noBp, HiResOutputMode.BIT_PERFECT, 96_000, 2, 1f) as Configure
        assertThat(d.bitPerfect).isFalse()
        assertThat(d.bitPerfectUnavailable).isTrue()
        assertThat(d.bitPerfectMiss).isEqualTo(BitPerfectMiss.NOT_OFFERED)
        assertThat(d.trackFormat).isEqualTo(SampleFormat.FLOAT)
    }

    @Test fun `speed other than 1x can't be bit-perfect`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 48_000, 2, 1.25f) as Configure
        assertThat(d.bitPerfect).isFalse()
        assertThat(d.option.sampleRate).isEqualTo(48_000)
        assertThat(d.bitPerfectMiss).isEqualTo(BitPerfectMiss.SPEED)
    }

    @Test fun `Listen Together's hold plans rate matching even at 1x`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 48_000, 2, 1f, bitPerfectHeld = true) as Configure
        assertThat(d.bitPerfect).isFalse()
        assertThat(d.option.sampleRate).isEqualTo(48_000)
        assertThat(d.trackFormat).isEqualTo(SampleFormat.FLOAT)
        assertThat(d.bitPerfectMiss).isEqualTo(BitPerfectMiss.LISTEN_TOGETHER)
    }

    @Test fun `under the hold a drift nudge plans the same hardware set-up, so nothing rebuilds`() {
        // Without the hold 1.0x planned bit-perfect and 1.03x matched: a rebuild (re-seek) per nudge.
        val plans = listOf(0.97f, 1f, 1.03f).map { speed ->
            val d = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 48_000, 2, speed, bitPerfectHeld = true)
            OutputPlan(d, (d as Configure).trackFormat, null, Any())
        }
        assertThat(plans.map { it.key }.toSet()).hasSize(1)
        assertThat(plans.zipWithNext().none { (a, b) -> a.needsRebuildFor(b) }).isTrue()
    }

    @Test fun `a DAC with no bit-perfect mode says so, hold or not`() {
        val noBp = dac.filterNot { it.bitPerfect }
        val d = MixerSelection.choose(noBp, HiResOutputMode.BIT_PERFECT, 96_000, 2, 1f, bitPerfectHeld = true) as Configure
        assertThat(d.bitPerfectMiss).isEqualTo(BitPerfectMiss.NOT_OFFERED)
    }

    @Test fun `tuning with bit-perfect chosen falls back to matched, at the song's own rate, saying why`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 96_000, 2, 1f, tuned = true) as Configure
        assertThat(d.bitPerfect).isFalse()
        assertThat(d.option.sampleRate).isEqualTo(96_000)
        assertThat(d.trackFormat).isEqualTo(SampleFormat.FLOAT)
        assertThat(d.bitPerfectMiss).isEqualTo(BitPerfectMiss.TUNING)
        assertThat(OutputPlan(d, d.trackFormat, null, Any()).mixerPath).isEqualTo(MixerPath.MATCHED)
    }

    @Test fun `standard tuning leaves bit-perfect alone`() {
        val d = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 96_000, 2, 1f, tuned = false) as Configure
        assertThat(d.bitPerfect).isTrue()
        assertThat(d.bitPerfectMiss).isNull()
    }

    @Test fun `tuning is named over speed and over a Listen Together session`() {
        val sped = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 48_000, 2, 1.25f, tuned = true) as Configure
        assertThat(sped.bitPerfectMiss).isEqualTo(BitPerfectMiss.TUNING)
        val held = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 48_000, 2, 1f, bitPerfectHeld = true, tuned = true) as Configure
        assertThat(held.bitPerfectMiss).isEqualTo(BitPerfectMiss.TUNING)
    }

    @Test fun `a DAC with no bit-perfect mode still says that first when tuned`() {
        val noBp = dac.filterNot { it.bitPerfect }
        val d = MixerSelection.choose(noBp, HiResOutputMode.BIT_PERFECT, 96_000, 2, 1f, tuned = true) as Configure
        assertThat(d.bitPerfectMiss).isEqualTo(BitPerfectMiss.NOT_OFFERED)
    }

    @Test fun `tuning changes nothing in Match or Standard mode`() {
        assertThat(MixerSelection.choose(dac, HiResOutputMode.MATCH_RATE, 96_000, 2, 1f, tuned = true))
            .isEqualTo(MixerSelection.choose(dac, HiResOutputMode.MATCH_RATE, 96_000, 2, 1f))
        assertThat(MixerSelection.choose(dac, HiResOutputMode.STANDARD, 96_000, 2, 1f, tuned = true))
            .isEqualTo(Shared(Reason.MODE_STANDARD))
    }

    @Test fun `the hold changes nothing in Match mode`() {
        val held = MixerSelection.choose(dac, HiResOutputMode.MATCH_RATE, 96_000, 2, 1f, bitPerfectHeld = true)
        assertThat(held).isEqualTo(MixerSelection.choose(dac, HiResOutputMode.MATCH_RATE, 96_000, 2, 1f))
        assertThat((held as Configure).bitPerfectMiss).isNull()
    }

    @Test fun `bit-perfect needs the exact channel count, rate match does not`() {
        val mono = MixerSelection.choose(dac, HiResOutputMode.BIT_PERFECT, 44_100, 1, 1f) as Configure
        assertThat(mono.bitPerfect).isFalse() // no mono bit-perfect profile
        assertThat(mono.option.channelCount).isEqualTo(2) // shared stereo mixer upmixes
    }

    @Test fun `formats the platform can't express are ignored`() {
        val odd = listOf(opt(null, 96_000), opt(null, 96_000, bp = true))
        assertThat(MixerSelection.choose(odd, HiResOutputMode.MATCH_RATE, 96_000, 2, 1f))
            .isEqualTo(Shared(Reason.RATE_UNSUPPORTED_BY_DAC))
    }

    @Test fun `a 16-bit-only DAC still gets bit-perfect 16-bit`() {
        val old = listOf(opt(SampleFormat.INT16, 44_100, bp = true), opt(SampleFormat.INT16, 44_100))
        val d = MixerSelection.choose(old, HiResOutputMode.BIT_PERFECT, 44_100, 2, 1f) as Configure
        assertThat(d.bitPerfect).isTrue()
        assertThat(d.trackFormat).isEqualTo(SampleFormat.INT16)
    }
}
