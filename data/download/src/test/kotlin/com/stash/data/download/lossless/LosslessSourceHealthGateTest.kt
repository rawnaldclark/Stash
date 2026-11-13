package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import org.junit.Test

class LosslessSourceHealthGateTest {

    private var now = 1_000_000L
    private fun gate() = LosslessSourceHealthGate(nowMs = { now })

    @Test
    fun `fresh source is not degraded`() {
        assertThat(gate().isDegraded("kennyy_qobuz")).isFalse()
    }

    @Test
    fun `recordDegraded marks source degraded within cooldown`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }

    @Test
    fun `source recovers after cooldown lapses`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS + 1
        assertThat(g.isDegraded("kennyy_qobuz")).isFalse()
    }

    @Test
    fun `still degraded at the instant before cooldown ends`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS - 1
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }

    @Test
    fun `degrading one source does NOT gate another (independence)`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
        assertThat(g.isDegraded("squid_qobuz")).isFalse()
    }

    @Test
    fun `re-degrading extends the cooldown from the new instant`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS - 10
        g.recordDegraded("kennyy_qobuz") // re-degraded — window restarts
        now += 20 // past the ORIGINAL window, before the new one
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }

    @Test
    fun `a wrong file sets a source aside for the same cooldown as any degradation`() {
        // The player's check of an add-on's stream (AddonStreamCheck) and the download's format check
        // share this gate: both chains skip the add-on for the cooldown.
        val g = gate()
        g.recordWrongFile("addon:liar")
        assertThat(g.isDegraded("addon:liar")).isTrue()
        now += LosslessSourceHealthGate.COOLDOWN_MS
        assertThat(g.isDegraded("addon:liar")).isFalse()
    }

    @Test
    fun `setAside says which sources are set aside, until when and why, and stops saying it when the cooldown ends`() = kotlinx.coroutines.test.runTest {
        val g = LosslessSourceHealthGate(nowMs = { testScheduler.currentTime })
        val seen = mutableListOf<Map<String, LosslessSourceHealthGate.SetAside>>()
        val watching = launch { g.setAside.collect { seen += it } }
        testScheduler.runCurrent()
        assertThat(seen.last()).isEmpty()

        g.recordWrongFile("addon:liar")
        g.recordDegraded("addon:slow")
        testScheduler.runCurrent()
        val until = LosslessSourceHealthGate.COOLDOWN_MS
        assertThat(seen.last()).containsExactly(
            "addon:liar", LosslessSourceHealthGate.SetAside(until, wrongFile = true),
            "addon:slow", LosslessSourceHealthGate.SetAside(until, wrongFile = false),
        )

        // A moment before the end it still holds; at the end the screen is told it doesn't.
        testScheduler.advanceTimeBy(until - 1)
        testScheduler.runCurrent()
        assertThat(seen.last()).hasSize(2)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertThat(seen.last()).isEmpty()
        watching.cancel()
    }
}
