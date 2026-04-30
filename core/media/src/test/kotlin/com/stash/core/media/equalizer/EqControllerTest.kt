// EqControllerTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EqControllerTest {
  private val store = mockk<EqStore>(relaxed = true)
  private val migration = mockk<EqMigration>(relaxed = true)

  @OptIn(ExperimentalCoroutinesApi::class)
  @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

  @OptIn(ExperimentalCoroutinesApi::class)
  @After fun tearDown() { Dispatchers.resetMain() }

  @Test fun `init reads persisted state synchronously before exposing it`() = runBlocking {
    coEvery { store.read() } returns EqState(enabled = true, presetId = "rock")
    val ctrl = EqController(store, migration)
    ctrl.awaitInit()
    assertThat(ctrl.state.value.enabled).isTrue()
    assertThat(ctrl.state.value.presetId).isEqualTo("rock")
  }

  @Test fun `setEnabled flips the flag and triggers persist`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration)
    ctrl.awaitInit()
    ctrl.setEnabled(true)
    assertThat(ctrl.state.value.enabled).isTrue()
    advanceTimeBy(300)
    coVerify { store.write(match { it.enabled == true }) }
  }

  @Test fun `setBandGain clamps to spec range`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration)
    ctrl.awaitInit()
    ctrl.setBandGain(0, 100f)
    assertThat(ctrl.state.value.gainsDb[0]).isEqualTo(12f)
    ctrl.setBandGain(0, -100f)
    assertThat(ctrl.state.value.gainsDb[0]).isEqualTo(-12f)
  }

  @Test fun `setPreset updates gains from catalog`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration)
    ctrl.awaitInit()
    ctrl.setPreset("rock")
    val expected = PresetCatalog.byId("rock")!!.gainsDb
    assertThat(ctrl.state.value.gainsDb.contentEquals(expected)).isTrue()
    assertThat(ctrl.state.value.presetId).isEqualTo("rock")
  }

  @Test fun `flush forces immediate persist regardless of debounce`() = runTest {
    coEvery { store.read() } returns EqState()
    val ctrl = EqController(store, migration)
    ctrl.awaitInit()
    ctrl.setEnabled(true)
    ctrl.flush()
    coVerify { store.write(any()) }
  }
}
