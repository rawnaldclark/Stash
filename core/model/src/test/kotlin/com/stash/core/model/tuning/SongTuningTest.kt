package com.stash.core.model.tuning

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.TuningMath
import org.junit.Test

/** A song's stored reading, and the factor playback shifts it by. */
class SongTuningTest {

    @Test fun `a row never measured, one with nothing to read, and one measured`() {
        assertThat(SongTuning.of(null, null, null)).isEqualTo(SongTuning.NotMeasured)
        assertThat(SongTuning.of(12f, 0.9f, null)).isEqualTo(SongTuning.NotMeasured)
        assertThat(SongTuning.of(null, null, 1_000L)).isEqualTo(SongTuning.NoPitch) // couldn't be decoded
        assertThat(SongTuning.of(12f, 0.2f, 1_000L)).isEqualTo(SongTuning.NoPitch) // under the bar
        assertThat(SongTuning.of(Float.NaN, 0.9f, 1_000L)).isEqualTo(SongTuning.NoPitch)
        assertThat(SongTuning.of(12f, TuningEstimator.MIN_CONFIDENCE.toFloat(), 1_000L)).isEqualTo(SongTuning.Measured(12f))
    }

    @Test fun `a measured song reads as its own concert pitch`() {
        assertThat(SongTuning.Measured(-31.77f).hz).isWithin(0.01).of(432.0)
        assertThat(SongTuning.Measured(7.85f).hz).isWithin(0.01).of(442.0)
        assertThat(SongTuning.Measured(0f).hz).isWithin(1e-9).of(440.0)
    }

    @Test fun `only a measured song is corrected by its own offset`() {
        assertThat(SongTuning.Measured(21.7f).appliedCents).isEqualTo(21.7f)
        assertThat(SongTuning.NoPitch.appliedCents).isEqualTo(0f)
        assertThat(SongTuning.NotMeasured.appliedCents).isEqualTo(0f)
    }

    @Test fun `the factor is the reference over the song's own tuning`() {
        // A song at 442 brought to 432.
        assertThat(TuningMath.factor(432f, 7.85f).toDouble()).isWithin(1e-5).of(432.0 / 442.0)
        assertThat(TuningMath.shiftCents(432f, 7.85f)).isWithin(0.01).of(-39.62)
        // A standard recording gets what every song used to.
        assertThat(TuningMath.factor(432f, 0f)).isEqualTo(TuningMath.factor(432f))
        // A song already at 432 isn't moved.
        assertThat(TuningMath.factor(432f, -31.7667f).toDouble()).isWithin(1e-6).of(1.0)
        assertThat(TuningMath.shiftCents(432f, -31.7667f)).isWithin(0.001).of(0.0)
        // A song below the reference goes up.
        assertThat(TuningMath.factor(432f, -45.2f)).isGreaterThan(1f)
        assertThat(TuningMath.shiftCents(432f, -45.2f)).isWithin(0.01).of(13.43)
    }

    @Test fun `a reference far from standard still moves every song the whole way`() {
        // 415 (Baroque) is a semitone down: a song at 442 goes down a semitone and its own 7.85 cents.
        assertThat(TuningMath.shiftCents(415f, 7.85f)).isWithin(0.01).of(TuningMath.cents(415f) - 7.85)
        assertThat(TuningMath.factor(415f, 7.85f).toDouble()).isWithin(1e-5).of(415.0 / 442.0)
    }

    @Test fun `at standard nothing is shifted, whatever the song's tuning`() {
        assertThat(TuningMath.factor(440f, 21.7f)).isEqualTo(1f)
        assertThat(TuningMath.shiftCents(440f, 21.7f)).isEqualTo(0.0)
    }

    @Test fun `an offset that can't be one reads as a standard recording`() {
        assertThat(TuningMath.factor(432f, Float.NaN)).isEqualTo(TuningMath.factor(432f))
        assertThat(TuningMath.factor(432f, 900f)).isEqualTo(TuningMath.factor(432f, 50f))
    }
}
