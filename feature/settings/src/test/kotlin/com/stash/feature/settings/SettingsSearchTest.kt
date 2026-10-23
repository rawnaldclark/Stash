package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsSearchTest {

    private val index = listOf(
        SettingsSearchEntry("Theme", SettingsPage.APPEARANCE, listOf("dark mode", "light mode")),
        SettingsSearchEntry("Pure black (AMOLED)", SettingsPage.APPEARANCE, listOf("oled", "dark")),
        SettingsSearchEntry("Hi-Res output", SettingsPage.AUDIO, listOf("usb", "dac", "bit perfect")),
        SettingsSearchEntry("Equalizer", SettingsPage.EQUALIZER, listOf("eq", "bass boost")),
        SettingsSearchEntry("Streaming on Wi-Fi", SettingsPage.AUDIO),
        SettingsSearchEntry("Last.fm", SettingsPage.ACCOUNTS, listOf("scrobble")),
        SettingsSearchEntry("Save Data", SettingsPage.AUDIO, listOf("mobile data")),
    )
    private val pages = mapOf(
        SettingsPage.APPEARANCE to "Appearance",
        SettingsPage.AUDIO to "Audio & Quality",
        SettingsPage.EQUALIZER to "Audio & Quality › Equalizer",
        SettingsPage.ACCOUNTS to "Accounts & Sync",
    )

    private fun search(q: String) = searchSettings(q, index) { pages.getValue(it) }.map { it.title }

    @Test fun `a blank query finds nothing`() {
        assertThat(search("  ")).isEmpty()
    }

    @Test fun `every word must match, in any order`() {
        assertThat(search("dark theme")).containsExactly("Theme")
        assertThat(search("perfect bit")).containsExactly("Hi-Res output")
    }

    @Test fun `words match by their start`() {
        assertThat(search("equal")).containsExactly("Equalizer")
        assertThat(search("eq")).containsExactly("Equalizer")
    }

    @Test fun `joined spellings find hyphenated and dotted names`() {
        assertThat(search("wifi")).containsExactly("Streaming on Wi-Fi")
        assertThat(search("lastfm")).containsExactly("Last.fm")
    }

    @Test fun `a name match ranks above a synonym match`() {
        // "data" is in Save Data's name and only in Save Data's synonyms; "dark" only in synonyms.
        assertThat(search("dark")).containsExactly("Theme", "Pure black (AMOLED)").inOrder()
        assertThat(search("save")).containsExactly("Save Data")
    }

    @Test fun `the page name finds what is on it`() {
        assertThat(search("appearance")).containsExactly("Theme", "Pure black (AMOLED)").inOrder()
    }

    @Test fun `case and punctuation do not matter`() {
        assertThat(search("HI-RES")).containsExactly("Hi-Res output")
        assertThat(search("amoled")).containsExactly("Pure black (AMOLED)")
    }
}
