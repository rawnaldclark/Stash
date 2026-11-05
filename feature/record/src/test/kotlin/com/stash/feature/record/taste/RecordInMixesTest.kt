package com.stash.feature.record.taste

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.StashMixRecipeEntity
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
 * Off then on again is no change; with no taste nothing is made. Turned on when Daily Discover has never been made, the first
 * one is made.
 */
class RecordInMixesTest {
    private class Rig(
        test: TestScope,
        var taste: Boolean = true,
        on: Boolean = true,
        /** The recipe's playlist: null is a Daily Discover that has never been made. */
        playlistId: Long? = 11L,
        active: Boolean = true,
    ) {
        val switch = RecordInMixesPreference.Fixed(on = on)
        val asked = mutableListOf<Pair<Long, Boolean>>()
        val rebuilds get() = asked.size
        private val dd = StashMixRecipeEntity(id = RECIPE, name = RecordTaste.DAILY_DISCOVER, isBuiltin = true, playlistId = playlistId)
        val mixes = RecordInMixes(
            switch,
            hasTaste = { taste },
            dailyDiscover = { dd.takeIf { active } },
            make = { id, sameDay -> asked += id to sameDay },
            scope = test.backgroundScope,
        )
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

    @Test fun `a mix that exists is made again in today's order`() = runTest {
        val rig = Rig(this)
        rig.mixes.set(false)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.asked).containsExactly(RECIPE to true)
    }

    /** The import skips this with the switch off, so the tap is what opts in: it mustn't wait for the next launch. */
    @Test fun `turned on with no Daily Discover made yet, the first one is made`() = runTest {
        val rig = Rig(this, on = false, playlistId = null)
        rig.mixes.set(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.asked).containsExactly(RECIPE to false)
    }

    @Test fun `turned off with none made, nothing to make`() = runTest {
        val rig = Rig(this, on = true, playlistId = null)
        rig.mixes.set(false)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.asked).isEmpty()
    }

    @Test fun `turned on with none made and no taste, nothing to make`() = runTest {
        val rig = Rig(this, taste = false, on = false, playlistId = null)
        rig.mixes.set(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.asked).isEmpty()
    }

    @Test fun `turned on while Daily Discover isn't active, nothing to make`() = runTest {
        val rig = Rig(this, on = false, playlistId = null, active = false)
        rig.mixes.set(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(rig.asked).isEmpty()
    }

    private companion object {
        const val RECIPE = 7L
    }
}
