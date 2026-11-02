package com.stash.core.ui.ambient

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.stash.core.ui.theme.StashDarkColorScheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scheme swaps counted through the real [ProvideThemeAccent], as the scaffold composes it: each
 * swap recomposes the whole app, so the player's ticks, play/pause and a new cover file of the same
 * colour must cost none, an album change exactly one (one crossfade after it), and with Album colours
 * on Neutral the songs none at all. The test clock drives the settle delay, so the timings are exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeAccentSwapTest {

    // A test dispatcher for the effects, so the test clock drives the settle delay (and the effects run).
    @get:Rule
    val rule = createComposeRule(StandardTestDispatcher())

    private fun solid(argb: Long) = IntArray(400) { argb.toInt() }
    private val red = ambientPaletteFromPixels(solid(0xFFD32F2FL), key = "art:red")!!
    private val blue = ambientPaletteFromPixels(solid(0xFF1F3BFFL), key = "art:blue")!!

    private var palette by mutableStateOf(AmbientPalette.Default)
    private var followSong by mutableStateOf(true)
    private var shown: ColorScheme? = null
    private var swaps = 0

    /** Composes the provider on the dark theme with [initial] as the first palette (a cold start's seed). */
    private fun start(initial: AmbientPalette, follow: Boolean = true) {
        palette = initial
        followSong = follow
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme(colorScheme = StashDarkColorScheme) {
                ProvideThemeAccent(palette, followSong) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        if (scheme !== shown) {
                            if (shown != null) swaps++
                            shown = scheme
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /**
     * Applies pending state writes and runs two frames. On Robolectric's paused looper Compose's own
     * apply loop (a main-looper task) doesn't run as the test clock moves, so the test applies them:
     * once for the write itself, once more for what an effect wrote in response.
     */
    private fun frames() {
        repeat(2) {
            rule.runOnUiThread { Snapshot.sendApplyNotifications() }
            rule.mainClock.advanceTimeByFrame()
        }
    }

    private fun set(block: () -> Unit) {
        rule.runOnUiThread(block)
        frames()
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        frames()
    }

    private fun schemeOf(p: AmbientPalette) = ThemeAccent(p).scheme(StashDarkColorScheme, Ground.DARK)

    @Test fun `a cold start opens in the remembered colour and never swaps`() {
        start(red)
        assertEquals("first frame wears the album", schemeOf(red).primary, shown!!.primary)
        advance(5_000)
        assertEquals(0, swaps)
    }

    @Test fun `ticks, play and pause, and a new cover file of the same colour swap nothing`() {
        start(red)
        repeat(40) { i ->
            // The player re-emits the same album (a copy), or the next track's own cover file (a new key).
            set { palette = if (i % 2 == 0) red.copy() else red.copy(key = "art:local_track_$i.jpg") }
            advance(250)
        }
        advance(5_000)
        assertEquals(0, swaps)
    }

    @Test fun `an album change swaps once, one crossfade later`() {
        start(red)
        set { palette = blue }
        advance(SCHEME_SETTLE_MS - 100)
        assertEquals("not before the crossfade ends", 0, swaps)
        advance(200)
        assertEquals(1, swaps)
        assertEquals(schemeOf(blue).primary, shown!!.primary)
        advance(10_000)
        assertEquals(1, swaps)
    }

    @Test fun `on Neutral the songs never touch the scheme`() {
        start(red, follow = false)
        assertEquals("neutral from the first frame", StashDarkColorScheme.primary, shown!!.primary)
        set { palette = blue }
        advance(5_000)
        set { palette = red }
        advance(5_000)
        assertEquals(0, swaps)
        assertEquals(StashDarkColorScheme.primary, shown!!.primary)
    }

    @Test fun `switching Album colours swaps once, at once`() {
        start(red)
        set { followSong = false }
        advance(100)
        assertEquals(1, swaps)
        assertEquals(StashDarkColorScheme.primary, shown!!.primary)
        set { followSong = true }
        advance(100)
        assertEquals(2, swaps)
        assertNotEquals(StashDarkColorScheme.primary, shown!!.primary)
    }
}
