package com.stash.core.media.automix

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

class AutoMixBeatAnalyzerTest {
    @Test fun `steady click track yields a stable fractional grid and bar sections`() {
        val sampleRate = 22_050
        val seconds = 30
        val samples = FloatArray(sampleRate * seconds)
        val beatSamples = sampleRate / 2 // 120 BPM
        for (beat in 0 until seconds * 2) {
            val start = beat * beatSamples
            val amplitude = if (beat % 4 == 0) 0.95f else 0.70f
            for (i in 0 until 256) {
                val index = start + i
                if (index < samples.size) {
                    samples[index] += amplitude * (1f - i / 256f)
                }
            }
        }
        for (i in samples.indices) {
            samples[i] += (0.035 * sin(2.0 * PI * 55.0 * i / sampleRate)).toFloat()
        }

        val analysis = AutoMixBeatAnalyzer.analyze(samples, sampleRate, windowStartMs = 0L)
        assertThat(analysis).isNotNull()
        val grid = analysis!!.beatGrid
        assertThat(grid).isNotNull()
        assertThat(grid!!.bpm).isWithin(1.2f).of(120f)
        assertThat(grid.stability).isAtLeast(0.65f)
        assertThat(grid.beatStrength).isAtLeast(0.15f)
        assertThat(analysis.entryBeatGrid).isNotNull()
        assertThat(analysis.exitBeatGrid).isNotNull()
        assertThat(analysis.entryBeatGrid!!.bpm).isWithin(1.8f).of(120f)
        assertThat(analysis.exitBeatGrid!!.bpm).isWithin(1.8f).of(120f)
        assertThat(analysis.sections).isNotEmpty()
        assertThat(analysis.preferredEntryDownbeatMs).isNotNull()
        assertThat(analysis.preferredExitDownbeatMs).isNotNull()
    }

    @Test fun `sustained C major chord is recognised even without a beat`() {
        val sampleRate = 22_050
        val seconds = 12
        val samples = FloatArray(sampleRate * seconds)
        val notes = listOf(
            261.63 to 0.34, // C4
            329.63 to 0.22, // E4
            392.00 to 0.25, // G4
            523.25 to 0.18, // C5
        )
        for (i in samples.indices) {
            var value = 0.0
            for ((frequency, amplitude) in notes) {
                value += amplitude * sin(2.0 * PI * frequency * i / sampleRate)
            }
            samples[i] = value.toFloat().coerceIn(-1f, 1f)
        }

        val analysis = AutoMixBeatAnalyzer.analyze(samples, sampleRate, windowStartMs = 0L)
        assertThat(analysis).isNotNull()
        assertThat(analysis!!.musicalKey).isNotNull()
        assertThat(analysis.musicalKey!!.rootPitchClass).isEqualTo(0)
        assertThat(analysis.musicalKey!!.minor).isFalse()
        assertThat(analysis.sections).isNotEmpty()
        assertThat(analysis.sections.maxOf { it.melodic }).isAtLeast(0.40f)
        assertThat(analysis.sections.maxOf { it.sustained }).isAtLeast(0.65f)
    }
}
