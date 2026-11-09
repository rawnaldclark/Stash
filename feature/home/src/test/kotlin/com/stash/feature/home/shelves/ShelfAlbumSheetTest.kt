package com.stash.feature.home.shelves

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.stash.data.ytmusic.model.AlbumSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The New from your artists album sheet on screen. It opens fully, so its last row is on screen too: Material's
 * default stops halfway up first, which left View Artist below the edge, under the navigation bar, on a Pixel 5.
 * Taller than the screen, it scrolls to its last row. Its rows read as the song sheet beside it.
 */
@RunWith(RobolectricTestRunner::class)
class ShelfAlbumSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private val album = AlbumSummary("MPREb_1", "DOGS NEVER DIE", "ZekeUltra", null, "2026")
    private val rows = listOf("Play", "Shuffle", "Play Next", "Add to Queue", "View Album", "View Artist")

    private fun show() = rule.setContent {
        MaterialTheme {
            ShelfAlbumSheet(
                album = album,
                onPlay = {},
                onShuffle = {},
                onPlayNext = {},
                onAddToQueue = {},
                onOpenAlbum = {},
                onGoToArtist = {},
                onDismiss = {},
            )
        }
    }

    // About 470 dp of sheet: more than half of a 700 dp screen (so a half-open sheet hid the last row), less than all of it
    @Config(sdk = [34], qualifiers = "w393dp-h700dp")
    @Test fun `the sheet opens fully, its last row on screen`() {
        show()
        rule.waitForIdle()
        rows.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Config(sdk = [34], qualifiers = "w393dp-h360dp")
    @Test fun `on a screen shorter than the sheet it scrolls to its last row`() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("View Artist").assertIsNotDisplayed()
        rule.onNodeWithText("View Artist").performScrollTo().assertIsDisplayed()
    }
}
