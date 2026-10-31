package com.stash.feature.record.taste

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.RecordInMixesPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Use your record in mixes" (stash-player `src/lib/discover/record-switch.ts` `rebuildOnSwitch`): a flip waits 1.5 s for
 * another; then, when it changed what Daily Discover was last made with and there is a taste, today's mix is made again once.
 * Off then on again is no change; with no taste nothing is made.
 */
class RecordInMixesTest {
    private class Rig(test: TestScope, var taste: Boolean = true) {
        val switch = RecordInMixesPreference.Fixed(on = true)
        var rebuilds = 0
        val mixes = RecordInMixes(switch, hasTaste = { taste }, rebuild = { rebuilds++; true }, scope = test.backgroundScope)
    }

    @Test fun `a flip is kept at once and makes today's mix again once, 1,5 s after the last`() = runTest {
        val rig = Rig(this)
        rig.mixes.set(false)
        runCurrent()
        assertThat(rig.switch.enabled.first()).isFalse()
        advanceTimeBy(1_400)
        runCurrent()
        assertThat(rig.rebuilds).isEqualTo(0)
        advanceTimeBy(200)
        runCurrent()
        assertThat(rig.rebuilds).isEqualTo(1)
        // Settled: nothing more
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(rig.rebuilds).isEqualTo(1)
    }

    @Test fun `off then on again within the pause is no change`() = runTest {
        val rig = Rig(this)
        rig.mixes.set(false)
        advanceTimeBy(1_000)
        rig.mixes.set(true)
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(rig.rebuilds).isEqualTo(0)
        assertThat(rig.switch.enabled.first()).isTrue()
    }

    @Test fun `quick flips wait for the last, each settled change makes it once`() = runTest {
        val rig = Rig(this)
        rig.mixes.set(false)
        advanceTimeBy(500)
        rig.mixes.set(true)
        advanceTimeBy(500)
        rig.mixes.set(false)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.rebuilds).isEqualTo(1)
        rig.mixes.set(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.rebuilds).isEqualTo(2)
    }

    @Test fun `no taste, nothing to make again`() = runTest {
        val rig = Rig(this, taste = false)
        rig.mixes.set(false)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.rebuilds).isEqualTo(0)
    }
}
