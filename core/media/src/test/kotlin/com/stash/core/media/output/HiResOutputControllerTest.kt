package com.stash.core.media.output

import android.content.Context
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.HiResOutputMode
import com.stash.core.data.prefs.HiResOutputPreference
import com.stash.core.data.prefs.TuningPreference
import com.stash.core.media.equalizer.dsp.PitchStage
import com.stash.core.media.tuning.TuningController
import com.stash.core.media.tuning.TuningEngine
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.media.equalizer.dsp.FloatDspChain
import com.stash.core.media.output.HiResOutputController.Companion.RELEASE_GRACE_MS
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What the controller publishes: only the main playback's streams, and only
 * while one exists. The search preview used to become the "audible" output
 * (its path on the sheet, a bit-perfect DAC retuned to its rate) and a
 * released stream's path stayed up for good, holding crossfade off.
 *
 * Plain JVM: Build.VERSION.SDK_INT reads 0 here, so no mixer preference is
 * ever applied, which keeps these tests about publishing alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiResOutputControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val context = mockk<Context> {
        every { getSystemService(AudioManager::class.java) } returns audioManager
    }
    private val preference = object : HiResOutputPreference {
        override val mode = MutableStateFlow(HiResOutputMode.BIT_PERFECT)
        override suspend fun setMode(mode: HiResOutputMode) {
            this.mode.value = mode
        }
    }
    private val eq = mockk<EqController> { every { state } returns MutableStateFlow(EqState()) }
    private val loudness = mockk<LoudnessController> { every { state } returns MutableStateFlow(LoudnessState()) }
    private val tuningPreference = object : TuningPreference {
        override val referenceHz = MutableStateFlow(440f)
        override suspend fun setReferenceHz(hz: Float) {
            referenceHz.value = hz
        }
    }
    private val engine = object : TuningEngine {
        override val available = true
        override fun newStage(sampleRate: Int, channels: Int): PitchStage? = null
    }
    private lateinit var tuning: TuningController
    private lateinit var controller: HiResOutputController

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        tuning = TuningController(tuningPreference, engine)
        controller = HiResOutputController(context, preference, eq, loudness, tuning)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun bitPerfectPlan(): OutputPlan {
        val option = MixerOption(SampleFormat.INT24, 48_000, 2, bitPerfect = true, index = 0)
        return OutputPlan(MixerDecision.Configure(option, SampleFormat.INT24), SampleFormat.INT24, null, Any())
    }

    private fun sharedPlan() =
        OutputPlan(MixerDecision.Shared(MixerDecision.Reason.NOT_USB), SampleFormat.FLOAT, null, null)

    private fun stream(mainPlayback: Boolean, plan: OutputPlan) = StashAudioOutput(
        inner = mockk(relaxed = true),
        inFormat = SampleFormat.FLOAT,
        outFormat = plan.trackFormat,
        channelCount = 2,
        streamSampleRate = 48_000,
        plan = plan,
        dsp = FloatDspChain({ EqState() }, { LoudnessState() }),
        controller = controller,
        session = OutputSession(mainPlayback),
        platformAttributes = mockk(relaxed = true),
        bufferTier = 1,
    )

    @Test fun `the signal path says when the music is retuned`() = runTest(dispatcher) {
        runCurrent()
        val song = stream(mainPlayback = true, plan = sharedPlan())
        song.play()
        runCurrent()
        assertThat(controller.signalPath.value!!.stages.first { it.title == "Stash" }.detail).startsWith("Untouched")

        tuningPreference.referenceHz.value = 432f
        runCurrent()
        assertThat(controller.signalPath.value!!.stages.first { it.title == "Stash" }.detail)
            .startsWith("Retuned to A = 432 Hz")
    }

    @Test fun `a preview is planned on the shared mixer whatever the mode`() = runTest(dispatcher) {
        runCurrent() // the stored mode (Bit-perfect) arrives
        val plan = controller.prepare(96_000, 2, OutputSession(mainPlayback = false), mockk(relaxed = true))
        assertThat(plan.mixerPath).isEqualTo(MixerPath.SHARED)
        assertThat(plan.trackFormat).isEqualTo(SampleFormat.FLOAT)
        assertThat(plan.device).isNull()
    }

    @Test fun `a preview never becomes the audible output`() = runTest(dispatcher) {
        runCurrent()
        val preview = stream(mainPlayback = false, plan = bitPerfectPlan())
        preview.play()
        runCurrent()
        assertThat(controller.output.value).isNull()
        assertThat(controller.bitPerfectEngaged.value).isFalse()
    }

    @Test fun `a preview played and finished under the paused song leaves its path alone`() = runTest(dispatcher) {
        runCurrent()
        val song = stream(mainPlayback = true, plan = bitPerfectPlan())
        song.play()
        runCurrent()
        val songPath = controller.output.value

        val preview = stream(mainPlayback = false, plan = sharedPlan())
        preview.play()
        preview.release()
        advanceTimeBy(RELEASE_GRACE_MS + 1)
        runCurrent()

        assertThat(controller.output.value).isEqualTo(songPath)
        assertThat(controller.bitPerfectEngaged.value).isTrue()
    }

    @Test fun `a released stream's path is cleared once nothing replaces it`() = runTest(dispatcher) {
        runCurrent()
        val song = stream(mainPlayback = true, plan = bitPerfectPlan())
        song.play()
        runCurrent()
        assertThat(controller.bitPerfectEngaged.value).isTrue()

        song.release()
        runCurrent()
        assertThat(controller.output.value).isNotNull() // still waiting for a successor

        advanceTimeBy(RELEASE_GRACE_MS + 1)
        runCurrent()
        assertThat(controller.output.value).isNull()
        assertThat(controller.bitPerfectEngaged.value).isFalse() // crossfade's hold lets go
        assertThat(controller.signalPath.value).isNull()
    }

    @Test fun `a rebuild's successor takes over without the path going blank`() = runTest(dispatcher) {
        runCurrent()
        val before = stream(mainPlayback = true, plan = bitPerfectPlan())
        before.play()
        runCurrent()

        before.release()
        stream(mainPlayback = true, plan = sharedPlan()).play()
        advanceTimeBy(RELEASE_GRACE_MS + 1)
        runCurrent()

        assertThat(controller.output.value?.mixer).isEqualTo(MixerPath.SHARED)
    }

    @Test fun `a crossfade handed back shows the outgoing song again at once`() = runTest(dispatcher) {
        runCurrent()
        val outgoing = stream(mainPlayback = true, plan = bitPerfectPlan())
        outgoing.play()
        val incoming = stream(mainPlayback = true, plan = sharedPlan())
        incoming.play() // the fade starts: the incoming is the audible stream
        assertThat(controller.output.value?.mixer).isEqualTo(MixerPath.SHARED)

        incoming.release() // fade handed back (a queue edit mid-fade): the outgoing plays on
        assertThat(controller.output.value?.mixer).isEqualTo(MixerPath.BIT_PERFECT)
        advanceTimeBy(RELEASE_GRACE_MS + 1)
        runCurrent()
        assertThat(controller.output.value?.mixer).isEqualTo(MixerPath.BIT_PERFECT)
    }

    @Test fun `a paused stream is not taken for the audible one`() = runTest(dispatcher) {
        runCurrent()
        val paused = stream(mainPlayback = true, plan = bitPerfectPlan())
        paused.play()
        paused.pause()
        val current = stream(mainPlayback = true, plan = sharedPlan())
        current.play()

        current.release()
        advanceTimeBy(RELEASE_GRACE_MS + 1)
        runCurrent()
        assertThat(controller.output.value).isNull()
    }

    @Test fun `an earlier release never cuts a later stream's grace short`() = runTest(dispatcher) {
        runCurrent()
        val first = stream(mainPlayback = true, plan = bitPerfectPlan())
        first.play()
        first.release() // t = 0: its clear is due at RELEASE_GRACE_MS

        advanceTimeBy(RELEASE_GRACE_MS / 2)
        val second = stream(mainPlayback = true, plan = sharedPlan())
        second.play()
        second.release() // its clear is due half a grace later than the first one

        advanceTimeBy(RELEASE_GRACE_MS / 2 + 1)
        runCurrent()
        assertThat(controller.output.value?.mixer).isEqualTo(MixerPath.SHARED) // the first clear stood down

        advanceTimeBy(RELEASE_GRACE_MS / 2)
        runCurrent()
        assertThat(controller.output.value).isNull()
    }
}
