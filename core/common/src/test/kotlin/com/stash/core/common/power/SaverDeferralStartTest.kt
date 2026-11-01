package com.stash.core.common.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SaverDeferral.startWhenDue]: the lossless config's start under Battery Saver. It defers only
 * while the last SUCCESS is recent and there is a cached copy to use, and never past the day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaverDeferralStartTest {

    private val day = 24 * 60 * 60 * 1000L
    private val hour = 60 * 60 * 1000L

    /** Wall clock = an arbitrary epoch + the test's virtual time. */
    private val epoch = 1_800_000_000_000L
    private fun TestScope.now() = epoch + currentTime

    private class Calls {
        var cacheLoads = 0
        var started = 0
    }

    private fun TestScope.start(saver: BatterySaver, lastSuccessMs: Long?, cached: Boolean, calls: Calls) = async {
        SaverDeferral.startWhenDue(
            saver = saver,
            lastSuccessMs = lastSuccessMs,
            nowMs = { now() },
            maxAgeMs = day,
            loadCached = { calls.cacheLoads++ },
            hasCached = { cached },
            startRefreshing = { calls.started++ },
        )
    }

    @Test
    fun `Saver off at start fetches at once`() = runTest {
        val calls = Calls()
        val result = start(FakeBatterySaver(on = false), lastSuccessMs = now() - hour, cached = true, calls)
        runCurrent()
        assertEquals(SaverDeferral.Start.SAVER_OFF_AT_START, result.await())
        assertEquals(1, calls.started)
    }

    @Test
    fun `under Saver, never a success fetches at once`() = runTest {
        val calls = Calls()
        val result = start(FakeBatterySaver(on = true), lastSuccessMs = null, cached = true, calls)
        runCurrent()
        assertEquals(SaverDeferral.Start.STALE, result.await())
        assertEquals(1, calls.started)
    }

    @Test
    fun `under Saver, a success a day old fetches at once`() = runTest {
        val calls = Calls()
        val result = start(FakeBatterySaver(on = true), lastSuccessMs = now() - day, cached = true, calls)
        runCurrent()
        assertEquals(SaverDeferral.Start.STALE, result.await())
    }

    @Test
    fun `under Saver, an empty cache fetches at once even after a recent success`() = runTest {
        // A first launch behind a captive portal stamped nothing; but even a stamp can't stand in
        // for a config that isn't there (cache cleared, unreadable): lossless would be off all day.
        val calls = Calls()
        val result = start(FakeBatterySaver(on = true), lastSuccessMs = now() - hour, cached = false, calls)
        runCurrent()
        assertEquals(SaverDeferral.Start.NO_CACHE, result.await())
        assertEquals("the cache was tried first", 1, calls.cacheLoads)
        assertEquals(1, calls.started)
    }

    @Test
    fun `under Saver with a cache, it waits for Saver to turn off`() = runTest {
        val saver = FakeBatterySaver(on = true)
        val calls = Calls()
        val result = start(saver, lastSuccessMs = now() - hour, cached = true, calls)
        runCurrent()
        assertEquals(1, calls.cacheLoads)
        advanceTimeBy(3 * hour)
        runCurrent()
        assertEquals("still deferred", 0, calls.started)

        saver.isOn.value = false
        runCurrent()
        assertEquals(SaverDeferral.Start.SAVER_TURNED_OFF, result.await())
        assertEquals(1, calls.started)
    }

    @Test
    fun `under Saver all day, it fetches when the last success turns a day old`() = runTest {
        // A process that stays alive with Saver on: the wait is capped at what is left of the day.
        val calls = Calls()
        val result = start(FakeBatterySaver(on = true), lastSuccessMs = now() - 20 * hour, cached = true, calls)
        runCurrent()
        advanceTimeBy(4 * hour - 1)
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, calls.started)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(SaverDeferral.Start.MAX_AGE_REACHED, result.await())
        assertEquals(1, calls.started)
    }
}
