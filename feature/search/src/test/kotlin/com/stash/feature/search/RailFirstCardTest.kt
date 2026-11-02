package com.stash.feature.search

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The artist page's rails when the enriched profile follows the YouTube one with a newer release
 * in front (Sonic Youth: the Qobuz "Live in Berlin…" ahead of "Hold That Tiger"). Each rail sits
 * in a LazyColumn item, as on the page, where it is composed during the page's own measure.
 *
 * Checked by where a card sits, not by "displayed": a card kept just off the left edge still
 * shows 4dp of itself inside the rail's 12dp padding, which counts as displayed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RailFirstCardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun yt(title: String, year: Int) =
        AlbumSummary(id = title, title = title, artist = "", thumbnailUrl = null, year = "$year")

    /** YouTube's shelf alone (the first paint), newest first. */
    private val youTube = listOf(
        yt("Hold That Tiger", 2025),
        yt("Sonic Nurse", 2004),
        yt("Murray Street", 2002),
        yt("Washing Machine", 1995),
        yt("Dirty", 1992),
        yt("Goo", 1990),
    )

    /** The enriched profile: a newer Qobuz release merged in front. */
    private val enriched = listOf(
        AlbumSummary(
            id = "qobuz-berlin", title = "Live in Berlin", artist = "Sonic Youth", thumbnailUrl = null,
            year = "2026-03-06", source = AlbumSource.QOBUZ, trackCount = 14,
        ),
    ) + youTube

    private fun latest(albums: List<AlbumSummary>) = albums.map { LatestRelease(it, it.year!!.take(4).toInt(), ReleaseKind.ALBUM) }

    /** The rail's first card on screen is [title]: at the rail's 12dp start padding. */
    private fun assertFirstCard(title: String) {
        rule.onNodeWithText(title).assertLeftPositionInRootIsEqualTo(RAIL_START)
    }

    /** The row, not the page's column around it (both can scroll to an index). */
    private val rail = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    @Test fun `Latest releases shows a newer release that arrives in front`() {
        var releases by mutableStateOf(latest(youTube))
        rule.setContent { LazyColumn { item { LatestReleasesRow(releases = releases, onClick = {}) } } }
        assertFirstCard("Hold That Tiger")

        releases = latest(enriched)

        assertFirstCard("Live in Berlin")
    }

    @Test fun `Latest releases scrolled by the user keeps its card`() {
        var releases by mutableStateOf(latest(youTube))
        rule.setContent { LazyColumn { item { LatestReleasesRow(releases = releases, onClick = {}) } } }
        rule.onNode(rail).performScrollToIndex(3)
        assertFirstCard("Washing Machine")

        releases = latest(enriched)

        assertFirstCard("Washing Machine")
    }

    @Test fun `Albums shows a newer album that arrives in front`() {
        var albums by mutableStateOf(youTube)
        rule.setContent { LazyColumn { item { AlbumsRow(albums = albums, onClick = {}) } } }
        assertFirstCard("Hold That Tiger")

        albums = enriched

        assertFirstCard("Live in Berlin")
    }

    @Test fun `Albums opened on an album from Now Playing stays on it`() {
        // The jump to "Hold That Tiger" has been shown; the enriched shelf then moves it to index 1.
        var albums by mutableStateOf(youTube)
        rule.setContent {
            LazyColumn {
                item {
                    AlbumsRow(
                        albums = albums,
                        onClick = {},
                        focusIndex = albums.indexOfFirst { it.title == "Hold That Tiger" },
                        focusShown = true,
                    )
                }
            }
        }
        assertFirstCard("Hold That Tiger")

        albums = enriched

        assertFirstCard("Hold That Tiger")
    }

    @Test fun `returns to the first card only when it changed and the rail was at its start`() {
        assertTrue(returnsToFirstCard(previousFirstKey = "tiger", firstKey = "berlin", firstVisibleIndex = 0))
        // The user had scrolled.
        assertFalse(returnsToFirstCard(previousFirstKey = "tiger", firstKey = "berlin", firstVisibleIndex = 3))
        // Same first card: nothing to show.
        assertFalse(returnsToFirstCard(previousFirstKey = "tiger", firstKey = "tiger", firstVisibleIndex = 0))
        // The first paint, and a rail emptied.
        assertFalse(returnsToFirstCard(previousFirstKey = null, firstKey = "tiger", firstVisibleIndex = 0))
        assertFalse(returnsToFirstCard(previousFirstKey = "tiger", firstKey = null, firstVisibleIndex = 0))
    }

    private companion object {
        /** LatestReleasesRow's and AlbumsRow's content padding. */
        val RAIL_START = 12.dp
    }
}
