package com.stash.feature.nowplaying.listen

import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.RoomMember
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.ui.FullArtTheme
import com.stash.feature.nowplaying.ui.NpInkLight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The colour "Listening with Mia" is laid out with, under the real FullArtTheme. A content colour no theme would use
 * stands in for whatever is above the bar: the title must wear Now Playing's ink, never that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionBarTitleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val room = ListenTogetherState.InRoom(
        code = "R2YHWS55",
        url = "https://example.invalid/l/R2YHWS55",
        myId = "me",
        isHost = true,
        hostId = "me",
        members = listOf(RoomMember("me", "Rawn"), RoomMember("mia", "Mia")),
        suggestions = emptyList(),
    )
    private val contentColor = Color(0xFF123456)

    private var appDark by mutableStateOf(true)
    private var fullArt by mutableStateOf(false)

    private fun show() {
        rule.setContent {
            StashTheme(darkTheme = appDark) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    FullArtTheme(on = fullArt) { SessionBar(room, onClick = {}) }
                }
            }
        }
    }

    private fun titleColor(): Color {
        rule.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        // The bar is clickable, so its words merge into one node; the title's own node is in the unmerged tree.
        rule.onNodeWithText("Listening with Mia", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    @Test
    fun `in the normal layout the title is white in a dark theme and plum-black in a light one`() {
        // Device-visible 2026-11-05: black on the dark ground (1.11:1), because nothing above the sheet gives a colour.
        show()
        assertEquals(Color.White, titleColor())
        appDark = false
        assertEquals(NpInkLight, titleColor())
    }

    @Test
    fun `on Full art the title is white, in a dark app and in a light one`() {
        fullArt = true
        show()
        assertEquals(Color.White, titleColor())
        appDark = false
        assertEquals(Color.White, titleColor())
    }

    @Test
    fun `in a light theme the title follows Full art turning on and off`() {
        appDark = false
        show()
        assertEquals(NpInkLight, titleColor())
        fullArt = true
        assertEquals(Color.White, titleColor())
        fullArt = false
        assertEquals(NpInkLight, titleColor())
    }
}
