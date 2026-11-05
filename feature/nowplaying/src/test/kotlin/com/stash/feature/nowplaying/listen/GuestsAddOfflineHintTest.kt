package com.stash.feature.nowplaying.listen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.RoomMember
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The line under the host's "Let guests add songs" switch, in the Session sheet's real content: the host in Offline
 * mode sees it with the switch on or off, a guest never does, and it follows the phone's mode while it's on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuestsAddOfflineHintTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val hint get() = rule.activity.getString(R.string.listen_guests_add_offline_hint)

    private fun room(isHost: Boolean, switchOn: Boolean = true, hostAppOutdated: Boolean = false) =
        ListenTogetherState.InRoom(
            code = "R2YHWS55",
            url = "https://example.invalid/l/R2YHWS55",
            myId = if (isHost) "host" else "guest",
            isHost = isHost,
            hostId = "host",
            members = listOf(RoomMember("host", "Rawn"), RoomMember("guest", "Maya")),
            suggestions = emptyList(),
            roomKnowsAdds = true,
            myGuestsAdd = switchOn,
            hostAppOutdated = hostAppOutdated,
        )

    /** The sheet's content, with a room and a mode the test can change while it's on screen. */
    private fun show(initialRoom: ListenTogetherState.InRoom, offline: Boolean): Screen {
        val screen = Screen(initialRoom, offline)
        rule.setContent {
            StashTheme(darkTheme = true) { sheetContent(screen.room, screen.offline) }
        }
        rule.waitForIdle()
        return screen
    }

    private class Screen(room: ListenTogetherState.InRoom, offline: Boolean) {
        var room by mutableStateOf(room)
        var offline by mutableStateOf(offline)
    }

    @androidx.compose.runtime.Composable
    private fun sheetContent(room: ListenTogetherState.InRoom, offline: Boolean) {
        RoomContent(
            room = room,
            onAnswer = { _, _ -> },
            onMakeHost = {},
            onEditQueue = { _, _ -> },
            onCopyLink = {},
            onShare = {},
            onLeave = {},
            onEnd = {},
            offlineMode = offline,
            onGuestsAddChange = {},
            onUnqueue = { _, _ -> },
        )
    }

    private fun assertHint(shown: Boolean) {
        rule.waitForIdle()
        val node = rule.onNodeWithText(hint)
        if (shown) node.assertExists() else node.assertDoesNotExist()
    }

    @Test
    fun `the host in Offline sees it with the switch on and with the switch off`() {
        val screen = show(room(isHost = true, switchOn = true), offline = true)
        assertHint(shown = true)
        // Off still needs it: a suggestion the host approves gets skipped too.
        screen.room = room(isHost = true, switchOn = false)
        assertHint(shown = true)
    }

    @Test
    fun `the host in Online doesn't, with the switch on or off`() {
        val screen = show(room(isHost = true, switchOn = true), offline = false)
        assertHint(shown = false)
        screen.room = room(isHost = true, switchOn = false)
        assertHint(shown = false)
    }

    @Test
    fun `a guest in Offline never sees it, even when the host's app is older`() {
        val screen = show(room(isHost = false), offline = true)
        assertHint(shown = false)
        screen.room = room(isHost = false, hostAppOutdated = true)
        assertHint(shown = false)
    }

    @Test
    fun `it comes and goes as the mode changes with the sheet open`() {
        val screen = show(room(isHost = true), offline = false)
        assertHint(shown = false)
        screen.offline = true
        assertHint(shown = true)
        screen.offline = false
        assertHint(shown = false)
    }

    @Test
    fun `a guest who's made host sees it, and the host who hands over stops seeing it`() {
        val screen = show(room(isHost = false), offline = true)
        assertHint(shown = false)
        screen.room = room(isHost = true)
        assertHint(shown = true)
        screen.room = room(isHost = false)
        assertHint(shown = false)
    }

    /** The whole way, as Now Playing wires it: the phone's mode, through the view model, to the line on screen. */
    @Test
    fun `the line follows the phone's real mode through the view model`() {
        val streamingOn = MutableStateFlow(false) // Offline
        val together = ListenTogetherViewModel(
            controller = mockk<ListenTogetherController>(relaxed = true) {
                every { state } returns MutableStateFlow(ListenTogetherState.Idle)
            },
            sharePreference = mockk(relaxed = true) { coEvery { displayName() } returns "Rawn" },
            playerRepository = mockk(relaxed = true),
            streamingPreference = mockk<StreamingPreference> { every { enabled } returns streamingOn },
        )
        rule.setContent {
            val offline by together.offlineMode.collectAsStateWithLifecycle()
            StashTheme(darkTheme = true) { sheetContent(room(isHost = true), offline) }
        }
        assertHint(shown = true)
        streamingOn.value = true // Online
        assertHint(shown = false)
        streamingOn.value = false
        assertHint(shown = true)
    }
}
