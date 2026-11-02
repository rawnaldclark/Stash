package com.stash.feature.search

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
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

    @Test fun `returns to the first card only when it changed and the rail was at its start, at rest`() {
        fun returns(previous: String?, first: String?, index: Int = 0, offset: Int = 0, scrolling: Boolean = false) =
            returnsToFirstCard(previous, first, index, offset, scrolling)
        assertTrue(returns("tiger", "berlin"))
        // The user had scrolled.
        assertFalse(returns("tiger", "berlin", index = 3))
        // The user nudged it: its first card is partly off the edge.
        assertFalse(returns("tiger", "berlin", offset = 20))
        // The user is swiping it, or it's flinging: snapping it would pull it from under them.
        assertFalse(returns("tiger", "berlin", scrolling = true))
        // Same first card: nothing to show.
        assertFalse(returns("tiger", "tiger"))
        // The first paint, and a rail emptied.
        assertFalse(returns(null, "tiger"))
        assertFalse(returns("tiger", null))
    }

    @Test fun `Latest releases nudged by the user keeps its card where it is`() {
        var releases by mutableStateOf(latest(youTube))
        rule.setContent { LazyColumn { item { LatestReleasesRow(releases = releases, onClick = {}) } } }
        rule.onNode(rail).performSemanticsAction(SemanticsActions.ScrollBy) { it(20f, 0f) }
        val nudged = rule.onNodeWithText("Hold That Tiger").getUnclippedBoundsInRoot().left
        assertTrue("nudged off its start", nudged < RAIL_START)

        releases = latest(enriched)

        rule.onNodeWithText("Hold That Tiger").assertLeftPositionInRootIsEqualTo(nudged)
    }

    @Test fun `a rail flinging keeps its card and its fling`() {
        // At its start (index 0, offset 0) with a scroll in progress at a fling's priority, Default.
        // requestScrollToItem launches its own scroll {} at Default to stop it, which cancels it.
        var keys by mutableStateOf(listOf("tiger", "nurse", "murray", "washing"))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        // In a LazyColumn item, as the page's rails are, and its cards passed in as theirs are.
        rule.setContent {
            scope = rememberCoroutineScope()
            LazyColumn {
                item {
                    state = rememberLazyListState()
                    KeyedRail(state, keys)
                }
            }
        }
        val fling = rule.runOnIdle { scope.launch { state.scroll(MutatePriority.Default) { awaitCancellation() } } }
        rule.runOnIdle { assertTrue(state.isScrollInProgress) }

        keys = listOf("berlin") + keys

        rule.runOnIdle {
            assertTrue("the fling goes on", fling.isActive)
            assertEquals("still on its old first card", 1, state.firstVisibleItemIndex)
            fling.cancel()
        }
    }

    @Test fun `Fans also like shows a new first artist from a refreshed profile`() {
        var artists by mutableStateOf(
            listOf(
                ArtistSummary(id = "kim", name = "Kim Gordon", avatarUrl = null),
                ArtistSummary(id = "thurston", name = "Thurston Moore", avatarUrl = null),
                ArtistSummary(id = "lee", name = "Lee Ranaldo", avatarUrl = null),
                ArtistSummary(id = "dinosaur", name = "Dinosaur Jr.", avatarUrl = null),
            ),
        )
        rule.setContent { LazyColumn { item { RelatedArtistsRow(artists = artists, onClick = {}) } } }
        assertFirstCard("Kim Gordon")

        artists = listOf(ArtistSummary(id = "pavement", name = "Pavement", avatarUrl = null)) + artists

        assertFirstCard("Pavement")
    }

    @Test fun `Albums opened on an album from Now Playing whose jump hasn't run yet lands on it`() {
        // The enriched shelf moves the album to index 1 before its jump was shown: the jump runs
        // for its new place, and the rail isn't sent to the new first card instead.
        var albums by mutableStateOf(youTube)
        rule.setContent {
            LazyColumn {
                item {
                    AlbumsRow(
                        albums = albums,
                        onClick = {},
                        focusIndex = albums.indexOfFirst { it.title == "Hold That Tiger" },
                        focusShown = false,
                    )
                }
            }
        }

        albums = enriched

        assertFirstCard("Hold That Tiger")
    }

    /**
     * A keyed rail like the artist page's, its cards passed in: they change only with a
     * recomposition, as a row's list does. (Read from state inside the LazyRow, they'd move its
     * cards at measure, before ShowNewFirstCard's SideEffect saw the old position.)
     */
    @Composable
    private fun KeyedRail(state: LazyListState, keys: List<String>) {
        ShowNewFirstCard(state, firstKey = keys.first())
        LazyRow(state = state) { items(keys, key = { it }) { Text(it, Modifier.width(120.dp)) } }
    }

    private companion object {
        /** LatestReleasesRow's and AlbumsRow's content padding. */
        val RAIL_START = 12.dp
    }
}
