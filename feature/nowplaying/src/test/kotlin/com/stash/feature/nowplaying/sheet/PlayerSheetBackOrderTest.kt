package com.stash.feature.nowplaying.sheet

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back reaches the player before the NavHost, through the real OnBackPressedDispatcher.
 *
 * The NavHost registers its back callback while the Scaffold's content is subcomposed in layout,
 * after the root composition's effects ran; the newest callback wins. A [BoxWithConstraints]
 * (also subcomposed in layout) stands in for it here, composed before PlayerSheetBackHandler in
 * the same tree, as the scaffold has them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSheetBackOrderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var sheet: PlayerSheetState
    private var navHostBacks = 0

    private fun setUp(initial: PlayerSheetAnchor = PlayerSheetAnchor.MINI) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val s = androidx.compose.runtime.remember { PlayerSheetState(initial, scope) }
            SideEffect { sheet = s }
            // The NavHost's page stack: it always has a page to pop.
            BoxWithConstraints { BackHandler { navHostBacks++ } }
            PlayerSheetBackHandler(s)
        }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun `at MINI Back is the page's`() {
        setUp()
        back()
        assertEquals(1, navHostBacks)
        assertEquals(PlayerSheetAnchor.MINI, sheet.targetAnchor)
    }

    @Test
    fun `open, Back closes the player and leaves the page alone`() {
        setUp()
        rule.runOnUiThread { sheet.snapTo(PlayerSheetAnchor.FULL) }
        rule.waitForIdle()
        back()
        assertEquals(0, navHostBacks)
        assertEquals(PlayerSheetAnchor.MINI, sheet.targetAnchor)
    }

    @Test
    fun `restored open (the NavHost registered in the same first frame), Back still closes the player`() {
        setUp(initial = PlayerSheetAnchor.FULL)
        back()
        assertEquals(0, navHostBacks)
        assertEquals(PlayerSheetAnchor.MINI, sheet.targetAnchor)
    }

    @Test
    fun `lyrics first, then the player, then the page`() {
        setUp()
        var lyricsOpen = true
        rule.runOnUiThread {
            sheet.snapTo(PlayerSheetAnchor.FULL)
            sheet.registerBackConsumer { if (lyricsOpen) { lyricsOpen = false; true } else false }
        }
        rule.waitForIdle()
        back()
        assertEquals(false, lyricsOpen)
        assertEquals(PlayerSheetAnchor.FULL, sheet.targetAnchor)
        back()
        assertEquals(PlayerSheetAnchor.MINI, sheet.targetAnchor)
        assertEquals(0, navHostBacks)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        back()
        assertEquals(1, navHostBacks)
    }

    @Test
    fun `Back while the player is still closing is the player's, not the page's`() {
        setUp()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { sheet.snapTo(PlayerSheetAnchor.FULL) }
        rule.mainClock.advanceTimeBy(100)
        rule.runOnUiThread { sheet.collapse() }
        rule.mainClock.advanceTimeBy(100) // mid-close
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(16)
        assertEquals(0, navHostBacks)
        rule.mainClock.autoAdvance = true
    }
}
