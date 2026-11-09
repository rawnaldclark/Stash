package com.stash.core.media.tuning

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.TuningPreference
import com.stash.core.media.equalizer.dsp.FixedDelayFakeStage
import com.stash.core.media.equalizer.dsp.PitchStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

    private class Engine(override val available: Boolean, var failing: Boolean = false) : TuningEngine {
        override fun newStage(sampleRate: Int, channels: Int): PitchStage? =
            if (failing) null else FixedDelayFakeStage(channels, 8)
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
        assertThat(c.stageFailures.value).isEqualTo(0)
    }

    @Test fun `a format the engine can't build for is planned untuned, until the reference changes`() = runTest(dispatcher) {
        val engine = Engine(available = true, failing = true)
        val c = TuningController(preference, engine)
        runCurrent()
        assertThat(c.isRetunedFor(96_000, 2)).isTrue()

        assertThat(c.newStage(96_000, 2)).isNull()
        assertThat(c.stageFailures.value).isEqualTo(1) // the output controller replans on this
        assertThat(c.isRetunedFor(96_000, 2)).isFalse() // so bit-perfect can come back
        assertThat(c.isRetunedFor(44_100, 2)).isTrue() // other formats are unaffected
        assertThat(c.isRetuned()).isTrue()
        c.newStage(96_000, 2)
        assertThat(c.stageFailures.value).isEqualTo(1) // counted once per format

        engine.failing = false
        preference.referenceHz.value = 444f
        runCurrent()
        assertThat(c.isRetunedFor(96_000, 2)).isTrue() // a new choice tries again
    }

    @Test fun `an engine that throws counts as a failed stage`() = runTest(dispatcher) {
        val c = TuningController(preference, object : TuningEngine {
            override val available = true
            override fun newStage(sampleRate: Int, channels: Int): PitchStage = throw OutOfMemoryError("stage")
        })
        assertThat(c.newStage(48_000, 2)).isNull()
        assertThat(c.stageFailures.value).isEqualTo(1)
    }

    @Test fun `a store that fails its first read leaves playback at what's held`() {
        val broken = object : TuningPreference {
            override val referenceHz: Flow<Float> = flow { throw IOException("disk") }
            override suspend fun setReferenceHz(hz: Float) = Unit
        }
        // Nothing has run on main: the output stage's first read goes to the store, which throws.
        val c = TuningController(broken, Engine(available = true))
        assertThat(c.outputHz()).isEqualTo(440f)
        assertThat(c.outputHz()).isEqualTo(440f) // and doesn't throw on the next buffer either
    }
}
