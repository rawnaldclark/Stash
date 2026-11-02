package com.stash.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The album card's size. Home's album rows (New Releases, Top Albums, Qobuz Playlists, New from your
 * artists, Because you like) were 140 dp beside 120 dp mix rails; they now pass [HomeRailCardSize].
 * Every other screen keeps the 140 dp default, and the FLAC badge still sits inside the smaller cover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumSquareCardSizeTest {

    @get:Rule
    val rule = createComposeRule()

    // Longer than either card is wide: the title must ellipsize, not widen the card.
    private val title = "Atlantic Jazz Legends: The Complete Sessions, Remastered"

    private fun show(size: Dp? = null) {
        rule.setContent {
            MaterialTheme {
                val modifier = Modifier.testTag(CARD)
                if (size == null) {
                    AlbumSquareCard(
                        title = title, artist = "Queens Of The Stone Age", thumbnailUrl = null, year = "2026",
                        modifier = modifier, isLossless = true, onClick = {},
                    )
                } else {
                    AlbumSquareCard(
                        title = title, artist = "Queens Of The Stone Age", thumbnailUrl = null, year = "2026",
                        modifier = modifier, isLossless = true, size = size, onClick = {},
                    )
                }
            }
        }
    }

    @Test fun `Search, the artist page and Library keep the 140 dp card`() {
        show()
        rule.onNodeWithTag(CARD).assertWidthIsEqualTo(140.dp)
        rule.onNodeWithContentDescription(title, useUnmergedTree = true).assertWidthIsEqualTo(140.dp).assertHeightIsEqualTo(140.dp)
    }

    @Test fun `Home's rows get the mix rails' 120 dp card, cover and width`() {
        show(HomeRailCardSize)
        rule.onNodeWithTag(CARD).assertWidthIsEqualTo(120.dp)
        rule.onNodeWithContentDescription(title, useUnmergedTree = true).assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(120.dp)
    }

    @Test fun `the FLAC badge stays inside the 120 dp cover`() {
        show(HomeRailCardSize)
        val cover = rule.onNodeWithContentDescription(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = rule.onNodeWithText("FLAC", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The pill is inset 6 dp from the cover's bottom-right corner, and well clear of its left edge.
        assertTrue("badge right ${badge.right} vs cover ${cover.right}", badge.right <= cover.right - 6.dp)
        assertTrue("badge bottom ${badge.bottom} vs cover ${cover.bottom}", badge.bottom <= cover.bottom - 6.dp)
        assertTrue("badge left ${badge.left} vs cover ${cover.left}", badge.left >= (cover.left + cover.right) / 2)
        assertTrue("badge top ${badge.top} vs cover ${cover.top}", badge.top >= (cover.top + cover.bottom) / 2)
    }

    private companion object {
        const val CARD = "album-card"
    }
}
