package com.stash.core.model

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.TuningMath.Entry
import org.junit.Test

class TuningMathTest {

    @Test fun `432 Hz is 31_77 cents lower, a factor of 432 over 440`() {
        assertThat(TuningMath.cents(432f)).isWithin(0.005).of(-31.77)
        assertThat(TuningMath.factor(432f).toDouble()).isWithin(1e-7).of(432.0 / 440.0)
        assertThat(TuningMath.centsMagnitude(432f)).isEqualTo("31.8")
        assertThat(TuningMath.isLower(432f)).isTrue()
    }

    @Test fun `standard is zero cents, factor one, and not retuned`() {
        assertThat(TuningMath.cents(440f)).isWithin(1e-12).of(0.0)
        assertThat(TuningMath.factor(440f)).isEqualTo(1f)
        assertThat(TuningMath.isRetuned(440f)).isFalse()
        assertThat(TuningMath.isRetuned(440.04f)).isFalse() // rounds to 440.0
        assertThat(TuningMath.isRetuned(440.1f)).isTrue()
    }

    @Test fun `higher references read as higher`() {
        assertThat(TuningMath.cents(444f)).isWithin(0.01).of(15.67)
        assertThat(TuningMath.isLower(444f)).isFalse()
        // 415 is the baroque semitone down: about 101 cents.
        assertThat(TuningMath.cents(415f)).isWithin(0.01).of(-101.27)
    }

    @Test fun `values clamp to 392 and 494`() {
        assertThat(TuningMath.sanitize(300f)).isEqualTo(392f)
        assertThat(TuningMath.sanitize(391.9f)).isEqualTo(392f)
        assertThat(TuningMath.sanitize(600f)).isEqualTo(494f)
        assertThat(TuningMath.sanitize(494.04f)).isEqualTo(494f)
        assertThat(TuningMath.cents(1000f)).isEqualTo(TuningMath.cents(494f))
    }

    @Test fun `values keep one decimal`() {
        assertThat(TuningMath.sanitize(431.54f)).isEqualTo(431.5f)
        assertThat(TuningMath.sanitize(431.55f)).isEqualTo(431.6f)
        assertThat(TuningMath.sanitize(431.5f)).isEqualTo(431.5f)
    }

    @Test fun `anything that is not a number reads as standard`() {
        assertThat(TuningMath.sanitize(Float.NaN)).isEqualTo(440f)
        assertThat(TuningMath.sanitize(Float.POSITIVE_INFINITY)).isEqualTo(440f)
        assertThat(TuningMath.sanitize(Float.NEGATIVE_INFINITY)).isEqualTo(440f)
    }

    @Test fun `round trips through the label and the parser`() {
        for (hz in listOf(392f, 415f, 431.5f, 432f, 440f, 442f, 444f, 466.2f, 494f)) {
            val back = TuningMath.parse(TuningMath.number(hz)) as Entry.Value
            assertThat(back.hz).isEqualTo(hz)
            assertThat(back.clamped).isFalse()
            assertThat(TuningMath.factor(back.hz)).isEqualTo(TuningMath.factor(hz))
        }
    }

    @Test fun `the number shows a decimal only when it isn't whole`() {
        assertThat(TuningMath.number(432f)).isEqualTo("432")
        assertThat(TuningMath.number(431.5f)).isEqualTo("431.5")
        assertThat(TuningMath.number(440f)).isEqualTo("440")
        assertThat(TuningMath.number(466.2f)).isEqualTo("466.2")
    }

    @Test fun `the field takes up to three digits and one decimal place`() {
        assertThat(TuningMath.hzInput("")).isEqualTo("")
        assertThat(TuningMath.hzInput("4")).isEqualTo("4")
        assertThat(TuningMath.hzInput("432")).isEqualTo("432")
        assertThat(TuningMath.hzInput("432.")).isEqualTo("432.")
        assertThat(TuningMath.hzInput("431.5")).isEqualTo("431.5")
        assertThat(TuningMath.hzInput("431,5")).isEqualTo("431.5")
        assertThat(TuningMath.hzInput("431.55")).isNull()
        assertThat(TuningMath.hzInput("4321")).isNull()
        assertThat(TuningMath.hzInput("43a")).isNull()
        assertThat(TuningMath.hzInput("-432")).isNull()
        assertThat(TuningMath.hzInput("4.3.2")).isNull()
    }

    @Test fun `parse clamps out-of-range numbers and says so`() {
        assertThat(TuningMath.parse("500")).isEqualTo(Entry.Value(494f, clamped = true))
        assertThat(TuningMath.parse("43")).isEqualTo(Entry.Value(392f, clamped = true))
        assertThat(TuningMath.parse("431.5")).isEqualTo(Entry.Value(431.5f, clamped = false))
        assertThat(TuningMath.parse("431,5")).isEqualTo(Entry.Value(431.5f, clamped = false))
    }

    @Test fun `parse tells empty from invalid`() {
        assertThat(TuningMath.parse("")).isEqualTo(Entry.Empty)
        assertThat(TuningMath.parse("  ")).isEqualTo(Entry.Empty)
        assertThat(TuningMath.parse(".")).isEqualTo(Entry.Invalid)
        assertThat(TuningMath.parse("abc")).isEqualTo(Entry.Invalid)
        assertThat(TuningMath.parse("NaN")).isEqualTo(Entry.Invalid)
    }

    @Test fun `presets are all within range and start with standard`() {
        assertThat(TuningMath.PRESETS.first()).isEqualTo(440f)
        assertThat(TuningMath.PRESETS).containsExactly(440f, 432f, 444f, 442f, 415f).inOrder()
        assertThat(TuningMath.PRESETS.all { TuningMath.sanitize(it) == it }).isTrue()
        assertThat(TuningMath.QUICK_PRESETS).containsExactly(440f, 432f, 444f).inOrder()
    }
}
