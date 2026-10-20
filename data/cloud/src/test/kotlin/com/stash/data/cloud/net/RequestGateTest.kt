package com.stash.data.cloud.net

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestGateTest {

    private fun TestScope.gate(rps: Int = 5) = RequestGate(rps, clock = { currentTime }, sleep = { delay(it) })

    @Test
    fun `background requests are spaced to the rate`() = runTest {
        val gate = gate()
        val times = mutableListOf<Long>()
        repeat(5) { gate.acquire(RequestPriority.BACKGROUND); times += currentTime }
        assertThat(times).containsExactly(0L, 200L, 400L, 600L, 800L).inOrder()
    }

    @Test
    fun `interactive requests skip the queue`() = runTest {
        val gate = gate()
        repeat(3) { gate.acquire(RequestPriority.BACKGROUND) } // budget now booked to 600
        val t0 = currentTime
        gate.acquire(RequestPriority.INTERACTIVE)
        assertThat(currentTime - t0).isEqualTo(0L)
    }

    @Test
    fun `background yields while an interactive request waits out a back-off`() = runTest {
        val gate = gate()
        gate.backOff(1_000)
        val order = mutableListOf<String>()
        val bg = launch { gate.acquire(RequestPriority.BACKGROUND); order += "bg@$currentTime" }
        val fg = launch { gate.acquire(RequestPriority.INTERACTIVE); order += "fg@$currentTime" }
        advanceUntilIdle()
        bg.join(); fg.join()
        assertThat(order.first()).startsWith("fg@")
        assertThat(order.first()).isEqualTo("fg@1000")
    }

    @Test
    fun `retry-after pauses everyone, interactive waits are capped`() = runTest {
        val gate = RequestGate(5, maxInteractiveWaitMs = 2_000, clock = { currentTime }, sleep = { delay(it) })
        gate.backOff(30_000)
        val fg = async { gate.acquire(RequestPriority.INTERACTIVE); currentTime }
        val bg = async { gate.acquire(RequestPriority.BACKGROUND); currentTime }
        assertThat(fg.await()).isEqualTo(2_000L)
        assertThat(bg.await()).isAtLeast(30_000L)
    }

    @Test
    fun `parses retry-after seconds and dates, ignores junk`() {
        assertThat(RequestGate.parseRetryAfter("120")).isEqualTo(120_000L)
        assertThat(RequestGate.parseRetryAfter("1.5")).isEqualTo(1_500L)
        assertThat(RequestGate.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now = 1445412470000L)).isEqualTo(10_000L)
        assertThat(RequestGate.parseRetryAfter("soon")).isNull()
        assertThat(RequestGate.parseRetryAfter(null)).isNull()
    }

    @Test
    fun `a huge back-off is clamped`() = runTest {
        val gate = gate()
        gate.backOff(Long.MAX_VALUE / 2)
        assertThat(gate.remainingBackOffMs()).isEqualTo(RequestGate.MAX_BACKOFF_MS)
    }
}
