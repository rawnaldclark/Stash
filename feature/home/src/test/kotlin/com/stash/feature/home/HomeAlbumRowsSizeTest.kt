package com.stash.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.stash.core.data.home.BecauseRow
import com.stash.core.data.home.BecauseSeed
import com.stash.core.ui.components.HomeRailCardSize
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary
import com.stash.data.ytmusic.model.TrackSummary
import com.stash.feature.home.shelves.BecauseYouLikeRows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's album rows draw their cards at [HomeRailCardSize], the mix rails' 120 dp. Each of the three
 * places Home draws [com.stash.core.ui.components.AlbumSquareCard] is composed as Home composes it,
 * so a row that goes back to the card's 140 dp default fails here:
 * - [DiscoveryAlbumRow]: New Releases, Top Albums, New from your artists;
 * - [DiscoveryPlaylistRow]: Qobuz Playlists;
 * - [BecauseYouLikeRows]: Because you like.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeAlbumRowsSizeTest {

    @get:Rule
    val rule = createComposeRule()

    private val titles = listOf("Perfecth", "FIRESTARTER", "BE NOT AFRAID")

    private fun show(content: @Composable () -> Unit) = rule.setContent { MaterialTheme { content() } }

    /** The first two covers are 120 dp squares, one card plus the rail's 12 dp gap apart. */
    private fun assertMixRailSize() {
        val (first, second) = titles.take(2).map { title ->
            rule.onNodeWithContentDescription(title, useUnmergedTree = true)
                .assertWidthIsEqualTo(120.dp)
                .assertHeightIsEqualTo(120.dp)
                .getUnclippedBoundsInRoot()
        }
        assertEquals("step between cards", 120.dp + 12.dp, second.left - first.left)
    }

    @Test fun `New Releases, Top Albums and New from your artists draw 120 dp cards`() {
        val albums = titles.map { AlbumSummary(id = it, title = it, artist = "K/DA", thumbnailUrl = null, year = "2026") }
        show { DiscoveryAlbumRow(title = "Top Albums", albums = albums, onOpen = {}) }
        assertMixRailSize()
    }

    @Test fun `Qobuz Playlists draws 120 dp cards`() {
        val playlists = titles.map { PlaylistSummary(id = it, title = it, curator = "Qobuz", thumbnailUrl = null, trackCount = 30) }
        show { DiscoveryPlaylistRow(title = "Qobuz Playlists", playlists = playlists, onOpen = {}, onSeeAll = {}) }
        assertMixRailSize()
    }

    @Test fun `Because you like draws 120 dp cards`() {
        val songs = titles.map {
            TrackSummary(videoId = it, title = it, artist = "Paramore", album = null, durationSeconds = 200.0, thumbnailUrl = null)
        }
        val row = BecauseRow(BecauseSeed(videoId = "seed", title = "Ain't It Fun", artist = "Paramore", artUrl = null), songs)
        show { BecauseYouLikeRows(rows = listOf(row), onPlay = { _, _, _ -> }) }
        assertMixRailSize()
    }
}
