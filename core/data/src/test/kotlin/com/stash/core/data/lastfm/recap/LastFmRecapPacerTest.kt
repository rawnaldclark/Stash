package com.stash.core.data.lastfm.recap

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LastFmRecapPacerTest {

    @Test fun `two callers at once still go out one interval apart`() = runTest {
        val pacer = LastFmRecapPacer(220, elapsedMs = { testScheduler.currentTime })
        val times = java.util.Collections.synchronizedList(ArrayList<Long>())
        // Two callers (a story loading, Past recaps counting) racing for the same pacer.
        (0 until 2).map { caller ->
            async { repeat(5) { pacer.paced { times += testScheduler.currentTime } } }
        }.awaitAll()
        assertThat(times).hasSize(10)
        times.sorted().zipWithNext().forEach { (a, b) -> assertThat(b - a).isAtLeast(220L) }
    }

    @Test fun `the first request goes at once`() = runTest {
        val pacer = LastFmRecapPacer(220, elapsedMs = { testScheduler.currentTime })
        var at = -1L
        pacer.paced { at = testScheduler.currentTime }
        assertThat(at).isEqualTo(0L)
    }
}
