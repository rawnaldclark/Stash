// EqualizerViewModelTest.kt
package com.stash.feature.settings.equalizer

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EqualizerViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private val state = MutableStateFlow(EqState())
  private val ctrl = mockk<EqController>(relaxed = true).also {
    every { it.state } returns state
  }

  @Test fun `state flow forwards to UI state`() = runTest {
    val vm = EqualizerViewModel(ctrl)
    state.value = EqState(enabled = true, presetId = "rock")
    advanceUntilIdle()
    assertThat(vm.uiState.value.enabled).isTrue()
    assertThat(vm.uiState.value.activePresetId).isEqualTo("rock")
  }

  @Test fun `onToggle calls controller setEnabled`() {
    val vm = EqualizerViewModel(ctrl)
    vm.onToggle(true)
    verify { ctrl.setEnabled(true) }
  }

  @Test fun `onBandChanged calls controller setBandGain`() {
    val vm = EqualizerViewModel(ctrl)
    vm.onBandChanged(2, 4.5f)
    verify { ctrl.setBandGain(2, 4.5f) }
  }
}
