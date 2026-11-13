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

    @Test fun `the real index finds Search's mood and genre switch on Appearance`() {
        // Its name in English, every other setting a placeholder no query here can match (as YourStashSearchTest)
        val title = "Moods & genres in Search"
        val app = settingsSearchIndex { id -> if (id == R.string.settings_search_browse_title) title else "zz$id" }
        fun found(q: String) = searchSettings(q, app) { "Appearance" }.map { it.title }
        // By its name it comes first ("moods" also finds Home layout, by its words)
        for (q in listOf("moods", "genres", "search", "moods genres")) assertThat(found(q).first()).isEqualTo(title)
        // By its other words it's among the results ("browse" also finds Your Stash's "browser")
        for (q in listOf("browse", "moments", "genre tiles")) assertThat(found(q)).contains(title)
        assertThat(app.single { it.title == title }.page).isEqualTo(SettingsPage.APPEARANCE)
    }

    @Test fun `the real index finds the app language on Appearance`() {
        val title = "App language"
        val app = settingsSearchIndex { id -> if (id == R.string.settings_language_title) title else "zz$id" }
        fun found(q: String) = searchSettings(q, app) { "Appearance" }.map { it.title }
        for (q in listOf("language", "app language")) assertThat(found(q).first()).isEqualTo(title)
        for (q in listOf("locale", "translation")) assertThat(found(q)).contains(title)
        assertThat(app.single { it.title == title }.page).isEqualTo(SettingsPage.APPEARANCE)
    }

    @Test fun `the real index finds resume on headphones on Playback`() {
        val title = "Resume when headphones reconnect"
        val app = settingsSearchIndex { id -> if (id == R.string.settings_headphone_resume_on_reconnect_title) title else "zz$id" }
        fun found(q: String) = searchSettings(q, app) { "Playback" }.map { it.title }
        for (q in listOf("headphones", "resume", "reconnect")) assertThat(found(q).first()).isEqualTo(title)
        for (q in listOf("bluetooth", "earbuds", "unplug", "dac")) assertThat(found(q)).contains(title)
        assertThat(app.single { it.title == title }.page).isEqualTo(SettingsPage.PLAYBACK)
    }

    @Test fun `the real index sends youtube to the Lossless switch`() {
        val title = "Lossless"
        val app = settingsSearchIndex { id -> if (id == R.string.settings_lossless_title) title else "zz$id" }
        fun found(q: String) = searchSettings(q, app) { "Audio & Quality" }.map { it.title }
        for (q in listOf("youtube", "lossy")) assertThat(found(q)).contains(title)
        assertThat(app.single { it.title == title }.page).isEqualTo(SettingsPage.AUDIO)
    }

    @Test fun `the real index finds Use your record in mixes on Library and Storage`() {
        val title = "Use your record in mixes"
        val app = settingsSearchIndex { id -> if (id == R.string.settings_search_record_in_mixes) title else "zz$id" }
        fun found(q: String) = searchSettings(q, app) { "Library & Storage" }.map { it.title }
        for (q in listOf("your record", "record mixes")) assertThat(found(q).first()).isEqualTo(title)
        for (q in listOf("daily discover", "taste", "kept out", "spotify history")) assertThat(found(q)).contains(title)
        assertThat(app.single { it.title == title }.page).isEqualTo(SettingsPage.LIBRARY)
    }

    @Test fun `case and punctuation do not matter`() {
        assertThat(search("HI-RES")).containsExactly("Hi-Res output")
        assertThat(search("amoled")).containsExactly("Pure black (AMOLED)")
    }
}
