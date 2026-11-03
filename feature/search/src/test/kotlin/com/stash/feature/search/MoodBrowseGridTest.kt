package com.stash.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.stash.data.ytmusic.model.MoodTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MoodBrowseGrid] tells the screen which tiles are on it, as Search uses it: the grid leaves when the field is tapped,
 * a search typed or a tile opened, and comes back after. Gone, it reports no tile, so the cover reads still waiting
 * their turn aren't made during a search; back, it reports what's on screen again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoodBrowseGridTest {

    @get:Rule
    val rule = createComposeRule()

    private val moods = (1..4).map { MoodTile("Mood $it", "m$it", null) }
    private val genres = (1..30).map { MoodTile("Genre $it", "g$it", null) }

    /** The change composed, and the grid's 250 ms settle passed. */
    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test
    fun `the tiles on screen are reported, none once the grid is gone, and them again when it's back`() {
        val reports = mutableListOf<List<String>>()
        var shown by mutableStateOf(true)
        rule.setContent {
            if (shown) {
                MoodBrowseGrid(
                    moods = moods,
                    genres = genres,
                    covers = emptyMap(),
                    onOpen = {},
                    onTilesVisible = { tiles -> reports += tiles.map { it.params } },
                )
            }
        }
        settle()
        assertTrue(reports.last().containsAll(listOf("m1", "m2", "m3", "m4")))
        // Only what's on screen: the last genre is far below
        assertFalse("g30" in reports.last())

        // The field tapped: the grid leaves, and no tile is on screen any more
        shown = false
        settle()
        assertEquals(emptyList<String>(), reports.last())

        // Back to the grid: the tiles on screen are reported again
        val before = reports.size
        shown = true
        settle()
        assertTrue(reports.size > before)
        assertTrue(reports.last().containsAll(listOf("m1", "m2", "m3", "m4")))
    }
}
