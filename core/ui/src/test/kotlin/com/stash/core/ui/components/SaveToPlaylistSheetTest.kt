package com.stash.core.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.stash.core.ui.theme.StashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search box in "Save to Playlist": shown only for long lists, filters as you type
 * (ignoring case and accents), keeps "Create New Playlist" reachable, says so when nothing
 * matches, and a tap on a filtered row saves to that playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveToPlaylistSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private val many = listOf(
        "Road Trip", "Café Jazz", "Workout", "Focus", "Beyoncé Essentials",
        "Sleep", "Party", "Chill", "Throwbacks", "Gym Hits",
    ).mapIndexed { i, name -> PlaylistInfo(id = 100L + i, name = name, trackCount = i + 1) }

    private var saved: Long? = null

    private fun show(playlists: List<PlaylistInfo>) {
        saved = null
        rule.setContent {
            StashTheme {
                SaveToPlaylistSheet(
                    playlists = playlists,
                    onSaveToPlaylist = { saved = it },
                    onCreatePlaylist = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun filterIgnoresCaseAndAccents() {
        assertThat(filterPlaylists(many, "CAFE").map { it.name }).containsExactly("Café Jazz")
        assertThat(filterPlaylists(many, "beyonce").map { it.name }).containsExactly("Beyoncé Essentials")
        assertThat(filterPlaylists(many, "  ").size).isEqualTo(many.size)
    }

    @Test
    fun shortListHasNoSearchField() {
        show(many.take(PLAYLIST_SEARCH_MIN_PLAYLISTS - 1))
        rule.onNodeWithText("Road Trip").assertIsDisplayed()
        assertThat(rule.onAllNodesWithSetText()).isEmpty()
    }

    @Test
    fun typingFiltersTheList_andCreateStaysReachable() {
        show(many)
        rule.onNode(hasSetTextAction()).performTextInput("cafe")
        rule.onNodeWithText("Café Jazz").assertIsDisplayed()
        rule.onNodeWithText("Create New Playlist").assertIsDisplayed()
        assertThat(rule.onAllNodesByTextCount("Road Trip")).isEqualTo(0)
    }

    @Test
    fun noMatchSaysSo() {
        show(many)
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.onNodeWithText("No playlists match \"zzz\".").assertIsDisplayed()
        rule.onNodeWithText("Create New Playlist").assertIsDisplayed()
    }

    @Test
    fun pickingAFilteredPlaylistSavesToThatOne() {
        show(many)
        rule.onNode(hasSetTextAction()).performTextInput("work")
        // A semantic click: Robolectric's pointer events never reach a ModalBottomSheet's window (checked:
        // a pointer click on the old, search-less 3-row sheet saves nothing either), the handler is the same.
        rule.onNodeWithText("Workout").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(saved).isEqualTo(102L)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithSetText() =
        onAllNodes(hasSetTextAction()).fetchSemanticsNodes()

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesByTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size
}
