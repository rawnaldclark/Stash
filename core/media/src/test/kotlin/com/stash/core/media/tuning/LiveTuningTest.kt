package com.stash.core.media.tuning

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The rule for a song measured while it plays. */
class LiveTuningTest {

    private fun reading(cents: Double, confidence: Double, seconds: Double) = TuningMonitor.Reading(cents, confidence, seconds)

    @Test fun `nothing is applied before a reading is sure and rests on enough audio`() {
        val live = LiveTuning()
        assertThat(live.on(reading(21.0, 1.0, 8.0))).isEqualTo(LiveTuning.Decision(null, null))    // too soon
        assertThat(live.on(reading(21.0, 0.6, 16.0))).isEqualTo(LiveTuning.Decision(null, null))   // not sure enough
        assertThat(live.appliedCents).isNull()
    }

    @Test fun `the first sure reading is applied, once`() {
        val live = LiveTuning()
        assertThat(live.on(reading(21.7, 0.9, 12.0)).applyCents).isEqualTo(21.7f)
        assertThat(live.on(reading(21.9, 1.0, 16.0)).applyCents).isNull() // the same, within the measurement's own noise
        assertThat(live.appliedCents).isEqualTo(21.7f)
    }

    @Test fun `a reading that has moved is followed`() {
        val live = LiveTuning()
        live.on(reading(11.5, 1.0, 12.0)) // the intro
        val later = live.on(reading(2.4, 1.0, 44.0)) // the band comes in
        assertThat(later.applyCents).isEqualTo(2.4f)
    }

    @Test fun `a later reading needs less confidence than the first, but some`() {
        val live = LiveTuning()
        live.on(reading(11.5, 1.0, 12.0))
        assertThat(live.on(reading(2.4, 0.4, 44.0)).applyCents).isNull()
        assertThat(live.on(reading(2.4, 0.55, 48.0)).applyCents).isEqualTo(2.4f)
    }

    @Test fun `offsets either side of the half-semitone edge are close, not 98 cents apart`() {
        val live = LiveTuning()
        live.on(reading(49.0, 1.0, 12.0))
        assertThat(live.on(reading(-49.5, 1.0, 16.0)).applyCents).isNull()
    }

    @Test fun `a reading is kept once it rests on a minute, and again when it moves`() {
        val live = LiveTuning()
        assertThat(live.on(reading(21.7, 0.9, 12.0)).store).isNull()
        assertThat(live.on(reading(21.7, 1.0, 56.0)).store).isNull()
        assertThat(live.on(reading(21.9, 1.0, 60.0)).store).isEqualTo(LiveTuning.Stored(21.7f, 0.9f)) // what is applied
        assertThat(live.on(reading(21.8, 1.0, 64.0)).store).isNull()
        val moved = live.on(reading(15.0, 0.8, 120.0))
        assertThat(moved).isEqualTo(LiveTuning.Decision(15f, LiveTuning.Stored(15f, 0.8f)))
    }

    @Test fun `a song skipped before a minute keeps nothing`() {
        val live = LiveTuning()
        val decisions = listOf(12.0, 16.0, 40.0, 56.0).map { live.on(reading(5.0, 1.0, it)) }
        assertThat(decisions.mapNotNull { it.store }).isEmpty()
    }

    @Test fun `a song with nothing to read is kept as that after two minutes, once`() {
        val live = LiveTuning()
        assertThat(live.on(reading(28.6, 0.02, 60.0))).isEqualTo(LiveTuning.Decision(null, null))
        assertThat(live.on(reading(28.6, 0.02, 120.0))).isEqualTo(LiveTuning.Decision(null, LiveTuning.Stored(28.6f, 0.02f)))
        assertThat(live.on(reading(28.6, 0.02, 124.0)).store).isNull()
    }

    @Test fun `a song first read late is applied and kept in one step`() {
        val live = LiveTuning()
        live.on(reading(0.0, 0.3, 40.0))
        assertThat(live.on(reading(-4.7, 0.9, 64.0))).isEqualTo(LiveTuning.Decision(-4.7f, LiveTuning.Stored(-4.7f, 0.9f)))
    }
}
