package com.stash.core.media.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The widget's trailing debounce ([WidgetUpdateDebouncer]). */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdateDebouncerTest {

    private val songA = NowPlayingWidgetState("A", "Artist", "https://a", isPlaying = true)
    private val songB = NowPlayingWidgetState("B", "Artist", "https://b", isPlaying = true)

    private fun TestScope.debouncer(published: MutableList<NowPlayingWidgetState>) =
        WidgetUpdateDebouncer(
            scope = this,
            clock = { testScheduler.currentTime },
            publish = { published += it },
        )

    @Test
    fun `a burst of events goes out once, quiet ms after the last one`() = runTest {
        val published = mutableListOf<NowPlayingWidgetState>()
        val d = debouncer(published)

        d.submit(songA.copy(isPlaying = false)) // transition lands paused-buffering
        advanceTimeBy(100)
        d.submit(songB.copy(artUrl = null)) // the next song, before its cover
        advanceTimeBy(100)
        d.submit(songB) // its cover lands
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS - 1)
        runCurrent()
        assertThat(published).isEmpty()

        advanceTimeBy(2)
        runCurrent()
        assertThat(published).containsExactly(songB)
    }

    @Test
    fun `a player that never settles is still published by max wait`() = runTest {
        val published = mutableListOf<NowPlayingWidgetState>()
        val d = debouncer(published)

        // Flapping every 200 ms (under the quiet window) for 3 s.
        repeat(15) { i ->
            d.submit(songA.copy(isPlaying = i % 2 == 0))
            advanceTimeBy(200)
        }
        runCurrent()
        // Without the cap nothing would have gone out; with it, about one update per 1.5 s.
        assertThat(published).isNotEmpty()
        assertThat(published.size).isAtMost(3)
    }

    @Test
    fun `the same state is never published twice`() = runTest {
        val published = mutableListOf<NowPlayingWidgetState>()
        val d = debouncer(published)

        d.submit(songA)
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS + 1)
        d.submit(songA)
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS + 1)
        runCurrent()

        assertThat(published).containsExactly(songA)
    }

    @Test
    fun `a burst that comes back to what the widget shows sends nothing`() = runTest {
        val published = mutableListOf<NowPlayingWidgetState>()
        val d = debouncer(published)
        d.submit(songA)
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS + 1)

        d.submit(songA.copy(isPlaying = false)) // a quick pause …
        advanceTimeBy(50)
        d.submit(songA) // … and play again
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS + 1)
        runCurrent()

        assertThat(published).containsExactly(songA)
    }

    @Test
    fun `reset drops the pending state and lets the same state go out again`() = runTest {
        val published = mutableListOf<NowPlayingWidgetState>()
        val d = debouncer(published)
        d.submit(songA)
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS + 1)

        d.submit(songB)
        d.reset() // the service stopped before B settled
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS + 1)
        runCurrent()
        assertThat(published).containsExactly(songA)

        d.submit(songA) // a play on this same instance after the stop signal
        advanceTimeBy(WidgetUpdateDebouncer.QUIET_MS + 1)
        runCurrent()
        assertThat(published).containsExactly(songA, songA).inOrder()
    }
}
