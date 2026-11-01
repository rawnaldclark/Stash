package com.stash.core.common.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SystemBatterySaver]'s mapping from the system's power-save state to [BatterySaver.isOn], driven
 * through its read/subscribe seams (no Android framework), plus [awaitOff], [FakeBatterySaver],
 * [BatterySaver.Off] and [SaverDeferral].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatterySaverTest {

    /** A system whose power-save state is [on], with the change callback captured. */
    private class FakeSystem(var on: Boolean) {
        var onChange: (() -> Unit)? = null
        var subscribeCalls = 0
        val saver = SystemBatterySaver(
            read = { on },
            subscribe = { cb -> subscribeCalls++; onChange = cb },
        )

        fun flip(to: Boolean) {
            on = to
            onChange!!.invoke()
        }
    }

    @Test
    fun `starts from the system state when Battery Saver is on`() {
        assertTrue(FakeSystem(on = true).saver.isOn.value)
    }

    @Test
    fun `starts from the system state when Battery Saver is off`() {
        assertFalse(FakeSystem(on = false).saver.isOn.value)
    }

    @Test
    fun `follows every change broadcast`() {
        val system = FakeSystem(on = false)
        system.flip(true)
        assertTrue(system.saver.isOn.value)
        system.flip(false)
        assertFalse(system.saver.isOn.value)
    }

    @Test
    fun `subscribes once, before the first read`() {
        var subscribedBeforeRead = false
        var subscribed = false
        SystemBatterySaver(
            read = { subscribedBeforeRead = subscribed; false },
            subscribe = { subscribed = true },
        )
        assertTrue("a change between read and subscribe would be lost", subscribedBeforeRead)
        assertEquals(1, FakeSystem(on = false).subscribeCalls)
    }

    @Test
    fun `an unreadable state is off`() {
        val saver = SystemBatterySaver(read = { error("binder died") }, subscribe = {})
        assertFalse(saver.isOn.value)
    }

    @Test
    fun `a read that fails after a change leaves it off, and the next good read is taken`() {
        var mode = "on"
        var onChange: (() -> Unit)? = null
        val saver = SystemBatterySaver(
            read = { if (mode == "broken") error("binder died") else mode == "on" },
            subscribe = { onChange = it },
        )
        assertTrue(saver.isOn.value)
        mode = "broken"
        onChange!!()
        assertFalse(saver.isOn.value)
        mode = "on"
        onChange!!()
        assertTrue(saver.isOn.value)
    }

    @Test
    fun `a subscription that throws still reports the state at start`() {
        val saver = SystemBatterySaver(read = { true }, subscribe = { error("registration refused") })
        assertTrue(saver.isOn.value)
    }

    @Test
    fun `mapPowerSaveState passes the read through and maps a failure to off`() {
        assertTrue(SystemBatterySaver.mapPowerSaveState { true })
        assertFalse(SystemBatterySaver.mapPowerSaveState { false })
        assertFalse(SystemBatterySaver.mapPowerSaveState { throw SecurityException("no") })
    }

    @Test
    fun `Off is never on`() {
        assertFalse(BatterySaver.Off.isOn.value)
    }

    @Test
    fun `awaitOff returns at once when Battery Saver is off`() = runTest {
        FakeBatterySaver(on = false).awaitOff() // would hang the test if it suspended
    }

    @Test
    fun `awaitOff waits while Battery Saver is on and resumes when it turns off`() = runTest {
        val saver = FakeBatterySaver(on = true)
        val waiting = async { saver.awaitOff(); "resumed" }
        runCurrent()
        assertFalse(waiting.isCompleted)
        saver.isOn.value = false
        runCurrent()
        assertEquals("resumed", waiting.await())
    }

    @Test
    fun `SaverDeferral runs at once without Saver or a last run`() {
        assertTrue(SaverDeferral.due(saverOn = false, lastRunMs = 1_000L, nowMs = 1_001L, maxAgeMs = DAY))
        assertTrue(SaverDeferral.due(saverOn = true, lastRunMs = null, nowMs = 1_000L, maxAgeMs = DAY))
    }

    @Test
    fun `SaverDeferral defers a recent run under Saver and runs an old one`() {
        val last = 10 * DAY
        assertFalse(SaverDeferral.due(saverOn = true, lastRunMs = last, nowMs = last, maxAgeMs = DAY))
        assertFalse(SaverDeferral.due(saverOn = true, lastRunMs = last, nowMs = last + DAY - 1, maxAgeMs = DAY))
        assertTrue(SaverDeferral.due(saverOn = true, lastRunMs = last, nowMs = last + DAY, maxAgeMs = DAY))
        assertTrue(SaverDeferral.due(saverOn = true, lastRunMs = last, nowMs = last + 30 * DAY, maxAgeMs = DAY))
    }

    @Test
    fun `SaverDeferral runs when the last run is in the future (clock set back)`() {
        assertTrue(SaverDeferral.due(saverOn = true, lastRunMs = 10 * DAY, nowMs = 9 * DAY, maxAgeMs = DAY))
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
