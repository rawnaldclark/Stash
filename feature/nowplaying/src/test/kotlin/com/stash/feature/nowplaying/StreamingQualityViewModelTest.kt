package com.stash.feature.nowplaying

import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.prefs.StreamingQualityPreferences
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Streaming quality sheet reads and writes Settings' own streaming preferences (no setting of
 * its own), and every change has the next song resolved again, after the write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingQualityViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val lossless = MutableStateFlow(true)
    private val sourceSetUp = MutableStateFlow(true)
    private val saveData = MutableStateFlow(false)
    private val preferTidalAac = MutableStateFlow(false)
    private val streamOnly = MutableStateFlow(true)

    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxUnitFun = true) {
        every { enabled } returns lossless
    }
    private val qualityPrefs: StreamingQualityPreferences = mockk(relaxUnitFun = true) {
        every { saveData } returns this@StreamingQualityViewModelTest.saveData
        every { preferTidalAac } returns this@StreamingQualityViewModelTest.preferTidalAac
    }
    private val availability: LosslessAvailability = mockk {
        every { qbdlxEnabled } returns sourceSetUp
    }
    private val streaming: StreamingPreference = mockk {
        every { enabled } returns streamOnly
    }
    private val player: PlayerRepository = mockk(relaxUnitFun = true)

    /** Runs the app-wide scope's work on the test dispatcher, so it is done when a call returns. */
    private val userActions = object : UserActionScope {
        override val scope = CoroutineScope(dispatcher)
    }

    private lateinit var viewModel: StreamingQualityViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = StreamingQualityViewModel(losslessPrefs, qualityPrefs, availability, streaming, player, userActions)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `shows the switches as Settings has them`() = runTest(dispatcher) {
        assertEquals(
            StreamingQualityState(lossless = true, noLosslessSource = false, saveData = false, preferTidalAac = false),
            viewModel.state.value,
        )
        saveData.value = true
        preferTidalAac.value = true
        assertTrue(viewModel.state.value!!.saveData)
        assertTrue(viewModel.state.value!!.preferTidalAac)
    }

    @Test fun `with Lossless on but no source set up it says so, as Settings does`() = runTest(dispatcher) {
        sourceSetUp.value = false
        assertTrue(viewModel.state.value!!.noLosslessSource)
    }

    @Test fun `offered only while songs stream`() = runTest(dispatcher) {
        assertTrue(viewModel.streams.value)
        streamOnly.value = false
        assertFalse(viewModel.streams.value)
    }

    @Test fun `turning Lossless off writes Settings' switch, then resolves the next song again`() = runTest(dispatcher) {
        viewModel.onLosslessChanged(false)
        advanceUntilIdle() // the look-up waits for the taps to settle
        coVerifyOrder {
            losslessPrefs.setEnabled(false)
            player.reresolveNextStream()
        }
    }

    @Test fun `Save Data writes Settings' switch, then resolves the next song again`() = runTest(dispatcher) {
        viewModel.onSaveDataChanged(true)
        advanceUntilIdle() // the look-up waits for the taps to settle
        coVerifyOrder {
            qualityPrefs.setSaveData(true)
            player.reresolveNextStream()
        }
    }

    @Test fun `quick flips write each time but look the next song up once, after the last`() = runTest(dispatcher) {
        viewModel.onSaveDataChanged(true)
        advanceTimeBy(StreamingQualityViewModel.RERESOLVE_DEBOUNCE_MS / 2)
        viewModel.onSaveDataChanged(false)
        advanceTimeBy(StreamingQualityViewModel.RERESOLVE_DEBOUNCE_MS / 2)
        viewModel.onSaveDataChanged(true)
        coVerify(exactly = 0) { player.reresolveNextStream() }

        advanceUntilIdle()

        coVerify(exactly = 2) { qualityPrefs.setSaveData(true) }
        coVerify(exactly = 1) { qualityPrefs.setSaveData(false) }
        coVerify(exactly = 1) { player.reresolveNextStream() }
    }

    @Test fun `Prefer Tidal AAC writes Settings' switch, then resolves the next song again`() = runTest(dispatcher) {
        viewModel.onPreferTidalAacChanged(true)
        advanceUntilIdle() // the look-up waits for the taps to settle
        coVerifyOrder {
            qualityPrefs.setPreferTidalAac(true)
            player.reresolveNextStream()
        }
    }
}
