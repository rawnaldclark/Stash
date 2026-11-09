package com.stash.core.media.tuning

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.TuningPreference
import com.stash.core.media.equalizer.dsp.FixedDelayFakeStage
import com.stash.core.media.equalizer.dsp.PitchStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TuningControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val preference = object : TuningPreference {
        override val referenceHz = MutableStateFlow(432f)
        override suspend fun setReferenceHz(hz: Float) {
            referenceHz.value = hz
        }
    }

    private class Engine(override val available: Boolean) : TuningEngine {
        override fun newStage(sampleRate: Int, channels: Int): PitchStage = FixedDelayFakeStage(channels, 8)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `follows the stored reference`() = runTest(dispatcher) {
        val c = TuningController(preference, Engine(available = true))
        runCurrent()
        assertThat(c.referenceHz.value).isEqualTo(432f)
        assertThat(c.effectiveHz.value).isEqualTo(432f)
        assertThat(c.outputHz()).isEqualTo(432f)
        assertThat(c.isRetuned()).isTrue()

        preference.referenceHz.value = 440f
        runCurrent()
        assertThat(c.isRetuned()).isFalse()
    }

    @Test fun `a stream that starts before the store is read still opens retuned`() {
        // Nothing has run on main yet, so the collection hasn't delivered: the output stage reads the store itself.
        val c = TuningController(preference, Engine(available = true))
        assertThat(c.referenceHz.value).isEqualTo(440f)
        assertThat(c.outputHz()).isEqualTo(432f)
    }

    @Test fun `without the engine playback stays at 440 and no stage is built`() = runTest(dispatcher) {
        val c = TuningController(preference, Engine(available = false))
        runCurrent()
        assertThat(c.available).isFalse()
        assertThat(c.referenceHz.value).isEqualTo(432f) // the choice is kept for a phone that gains it
        assertThat(c.effectiveHz.value).isEqualTo(440f)
        assertThat(c.outputHz()).isEqualTo(440f)
        assertThat(c.isRetuned()).isFalse()
        assertThat(c.newStage(48_000, 2)).isNull()
    }

    @Test fun `stores a new reference, sanitized`() = runTest(dispatcher) {
        val c = TuningController(preference, Engine(available = true))
        c.setReferenceHz(431.55f)
        runCurrent()
        assertThat(preference.referenceHz.value).isEqualTo(431.6f)
        c.setReferenceHz(1000f)
        runCurrent()
        assertThat(c.referenceHz.value).isEqualTo(494f)
    }

    @Test fun `builds a stage for a stream when it has the engine`() = runTest(dispatcher) {
        val c = TuningController(preference, Engine(available = true))
        assertThat(c.newStage(44_100, 2)).isNotNull()
    }
}
