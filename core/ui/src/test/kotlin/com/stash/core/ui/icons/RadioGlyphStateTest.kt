package com.stash.core.ui.icons

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How the Signal glyph shows a radio ([RadioGlyphState.of]) and how its arcs breathe ([ringBreath]). */
class RadioGlyphStateTest {

    @Test
    fun `no station is off - still, in ink`() {
        val state = RadioGlyphState.of(onAir = false, tuning = false, playing = true)
        assertThat(state).isEqualTo(RadioGlyphState.OFF)
        assertThat(state.tinted).isFalse()
    }

    @Test
    fun `a playing station pulses, in the accent`() {
        val state = RadioGlyphState.of(onAir = true, tuning = false, playing = true)
        assertThat(state).isEqualTo(RadioGlyphState.PULSING)
        assertThat(state.tinted).isTrue()
    }

    @Test
    fun `a paused station is on but still`() {
        assertThat(RadioGlyphState.of(onAir = true, tuning = false, playing = false)).isEqualTo(RadioGlyphState.ON)
    }

    @Test
    fun `tuning is on but still (the radar sweep is the motion then)`() {
        assertThat(RadioGlyphState.of(onAir = false, tuning = true, playing = true)).isEqualTo(RadioGlyphState.ON)
        assertThat(RadioGlyphState.of(onAir = true, tuning = true, playing = true)).isEqualTo(RadioGlyphState.ON)
    }

    @Test
    fun `with the system's animations off a playing station holds still`() {
        assertThat(RadioGlyphState.of(onAir = true, tuning = false, playing = true, reducedMotion = true))
            .isEqualTo(RadioGlyphState.ON)
    }

    @Test
    fun `every state but off wears the accent`() {
        assertThat(RadioGlyphState.entries.filter { it.tinted })
            .containsExactly(RadioGlyphState.ON, RadioGlyphState.PULSING)
    }

    @Test
    fun `each arc's breath stays between rest and full`() {
        for (step in 0..100) for (ring in 0..1) {
            assertThat(ringBreath(step / 100f, ring)).isIn(com.google.common.collect.Range.closed(0f, 1f))
        }
    }

    @Test
    fun `the outer arc breathes after the inner one`() {
        // The inner arc peaks halfway through its active span; the outer one later, by the lag.
        val innerPeak = (0..1000).maxBy { ringBreath(it / 1000f, 0) } / 1000f
        val outerPeak = (0..1000).maxBy { ringBreath(it / 1000f, 1) } / 1000f
        assertThat(outerPeak).isGreaterThan(innerPeak)
        assertThat(ringBreath(innerPeak, 0)).isWithin(0.01f).of(1f)
    }

    @Test
    fun `each arc rests for part of the cycle (the breath is not a constant shimmer)`() {
        assertThat(ringBreath(0.9f, 0)).isEqualTo(0f)
        assertThat(ringBreath(0.0f, 1)).isEqualTo(0f) // still waiting for its lag
    }
}
