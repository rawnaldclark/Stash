package com.stash.feature.nowplaying.sheet

import androidx.compose.runtime.MonotonicFrameClock
import com.stash.core.media.service.StashPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetNavigationTest {

    private class TestFrameClock : MonotonicFrameClock {
        private var t = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16)
            t += 16_000_000L
            return onFrame(t)
        }
    }

    private fun TestScope.sheet(initial: PlayerSheetAnchor) =
        PlayerSheetState(initial, CoroutineScope(backgroundScope.coroutineContext + TestFrameClock()))

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun `the graph being set (a restore) never closes a restored player`() = runTest {
        val s = sheet(PlayerSheetAnchor.FULL)
        val collapser = CollapseOnNavigation(s)
        collapser.onDestinationChanged() // setGraph / restored back stack
        settle()
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
    }

    @Test
    fun `every later destination change closes an open player`() = runTest {
        val s = sheet(PlayerSheetAnchor.FULL)
        val collapser = CollapseOnNavigation(s)
        collapser.onDestinationChanged()
        collapser.onDestinationChanged() // an artist from Now Playing
        assertEquals(PlayerSheetAnchor.MINI, s.targetAnchor)
        settle()
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)

        s.snapTo(PlayerSheetAnchor.FULL)
        collapser.onDestinationChanged() // a link opened while the player is open
        settle()
        assertEquals(PlayerSheetAnchor.MINI, s.settledAnchor)
    }

    @Test
    fun `navigation at MINI leaves the player alone`() = runTest {
        val s = sheet(PlayerSheetAnchor.MINI)
        val collapser = CollapseOnNavigation(s)
        repeat(3) { collapser.onDestinationChanged() }
        assertTrue(s.isAtRest(PlayerSheetAnchor.MINI))
        assertFalse(s.isWarm)
    }

    @Test
    fun `the notification's intent opens the full player, nothing else does`() = runTest {
        assertEquals(PlayerSheetDeepLink.TARGET, PlayerSheetDeepLink.targetFor(StashPlaybackService.ACTION_OPEN_PLAYER))
        assertNull(PlayerSheetDeepLink.targetFor("android.intent.action.MAIN"))
        assertNull(PlayerSheetDeepLink.targetFor(null))

        val s = sheet(PlayerSheetAnchor.MINI)
        assertFalse(PlayerSheetDeepLink.handle("listen:abc", s))
        assertEquals(PlayerSheetAnchor.MINI, s.targetAnchor)
        assertTrue(PlayerSheetDeepLink.handle(PlayerSheetDeepLink.targetFor(StashPlaybackService.ACTION_OPEN_PLAYER), s))
        settle()
        assertEquals(PlayerSheetAnchor.FULL, s.settledAnchor)
    }
}
