package com.stash.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.stash.core.data.home.BecauseRow
import com.stash.core.data.home.BecauseSeed
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import com.stash.feature.home.shelves.BecauseYouLikeRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's long press on its song and album cards, composed as Home composes them: a Because you like card reports its
 * own song (the sheet opens for that song) and a tap still plays the row from it; a New from your artists card
 * reports its album; a row given no long press (Qobuz's New Releases and Top Albums) has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeShelfLongPressTest {

    @get:Rule
    val rule = createComposeRule()

    private val titles = listOf("Perfecth", "FIRESTARTER", "BE NOT AFRAID")

    private fun show(content: @Composable () -> Unit) = rule.setContent { MaterialTheme { content() } }

    private val songs = titles.map {
        TrackSummary(videoId = "v-$it", title = it, artist = "Paramore", album = null, durationSeconds = 200.0, thumbnailUrl = null)
    }
    private val row = BecauseRow(BecauseSeed(videoId = "seed", title = "Ain't It Fun", artist = "Paramore", artUrl = null), songs)
    private val albums = titles.map { AlbumSummary(id = "MPREb_$it", title = it, artist = "K/DA", thumbnailUrl = null, year = "2026") }

    @Test fun `a long press on a Because you like card opens that song's sheet, and a tap still plays the row`() {
        var more: TrackSummary? = null
        var played: Int? = null
        show { BecauseYouLikeRows(rows = listOf(row), onPlay = { _, index, _ -> played = index }, onMore = { more = it }) }
        rule.onNodeWithContentDescription("FIRESTARTER by Paramore").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(songs[1], more)
        assertNull("a long press doesn't play", played)
        rule.onNodeWithContentDescription("Perfecth by Paramore").performClick()
        rule.waitForIdle()
        assertEquals(0, played)
    }

    @Test fun `a long press on a New from your artists card reports its album`() {
        var more: AlbumSummary? = null
        var opened: AlbumSummary? = null
        show { DiscoveryAlbumRow(title = "New from your artists", albums = albums, onOpen = { opened = it }, lossless = false, onMore = { more = it }) }
        rule.onNodeWithContentDescription("FIRESTARTER").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(albums[1], more)
        assertNull("a long press doesn't open the album", opened)
    }

    @Test fun `an album row with no long press, as Qobuz's rows, offers none`() {
        show { DiscoveryAlbumRow(title = "Top Albums", albums = albums, onOpen = {}) }
        rule.onNodeWithContentDescription("Perfecth").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }
}
