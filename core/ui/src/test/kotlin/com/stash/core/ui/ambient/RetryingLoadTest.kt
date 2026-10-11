package com.stash.core.ui.ambient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The cover read behind the album colour: retried a few times, only on a signal, never in a loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryingLoadTest {

    @Test fun `a first success is the only read`() = runTest {
        var reads = 0
        val signals = MutableSharedFlow<Unit>()
        val out = mutableListOf<String>()
        backgroundScope.launch {
            retryingLoad(load = { reads++; "cover" }, fallback = "default", signals = signals, backoffMs = listOf(3_000)).collect { out += it }
        }
        runCurrent()
        signals.emit(Unit)
        advanceTimeBy(60_000)
        assertEquals(listOf("cover"), out)
        assertEquals(1, reads)
    }

    @Test fun `a failure shows the fallback and waits for a signal before retrying`() = runTest {
        var reads = 0
        val signals = MutableSharedFlow<Unit>()
        val out = mutableListOf<String>()
        backgroundScope.launch {
            retryingLoad(load = { reads++; if (reads < 2) null else "cover" }, fallback = "default", signals = signals, backoffMs = listOf(3_000, 10_000))
                .collect { out += it }
        }
        runCurrent()
        assertEquals(listOf("default"), out)
        advanceTimeBy(60_000) // no signal: no retry
        assertEquals(1, reads)
        signals.emit(Unit)
        advanceTimeBy(2_999)
        assertEquals(1, reads) // spaced by the backoff
        advanceTimeBy(2)
        assertEquals(2, reads)
        assertEquals(listOf("default", "cover"), out)
    }

    @Test fun `retries are bounded by the backoff list`() = runTest {
        var reads = 0
        val signals = MutableSharedFlow<Unit>()
        backgroundScope.launch {
            retryingLoad<String>(load = { reads++; null }, fallback = "default", signals = signals, backoffMs = listOf(3_000, 10_000)).collect {}
        }
        runCurrent()
        repeat(6) {
            signals.emit(Unit)
            advanceTimeBy(20_000)
        }
        assertEquals(3, reads) // the first read + 2 retries, then it gives up
    }

    @Test fun `signals during a backoff don't stack extra reads`() = runTest {
        var reads = 0
        val signals = MutableSharedFlow<Unit>()
        backgroundScope.launch {
            retryingLoad<String>(load = { reads++; null }, fallback = "default", signals = signals, backoffMs = listOf(3_000, 10_000, 30_000)).collect {}
        }
        runCurrent()
        signals.emit(Unit)
        repeat(5) { advanceTimeBy(500); signals.emit(Unit) } // a burst while waiting
        advanceTimeBy(1_000)
        assertEquals(2, reads)
    }
}
