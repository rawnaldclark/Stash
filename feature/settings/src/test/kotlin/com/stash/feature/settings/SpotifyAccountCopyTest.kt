package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The words a refused Spotify sign-in and a Refresh-mode Disconnect show, read from the English
 * strings. A slipped positional argument wouldn't fail until it reached a listener's screen, and
 * the refusal usually fires on a Reconnect: it has to say how to get the connected account back.
 */
class SpotifyAccountCopyTest {

    private val strings: Map<String, String> by lazy {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to
                node.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    @Test fun `the refusal names both accounts and says how to get each`() {
        val message = String.format(strings.getValue("settings_spotify_other_account_body"), "Rawn", "Sam")

        assertThat(message).isEqualTo(
            "Stash is connected to Spotify as Rawn, but you just signed in as Sam. " +
                "To reconnect Rawn, sign in again with Rawn's email or Google account. " +
                "To switch to Sam, tap Disconnect on the Spotify card first.",
        )
    }

    @Test fun `two accounts with one name get the same directions without naming the other`() {
        val message = String.format(strings.getValue("settings_spotify_other_account_body_unnamed"), "Rawn")

        assertThat(message).isEqualTo(
            "Stash is connected to Spotify as Rawn, but you just signed in with a different account. " +
                "To reconnect Rawn, sign in again with Rawn's email or Google account. " +
                "To switch accounts, tap Disconnect on the Spotify card first.",
        )
    }

    @Test fun `the Refresh note says when the playlists go and how they come back`() {
        val note = strings.getValue("settings_disconnect_spotify_refresh_note")

        // Disconnecting alone hides nothing: only a different account's first sync does.
        assertThat(note).contains("if you connect a different account")
        assertThat(note).contains("\"Undo last sync\" in Sync History brings them back")
    }
}
