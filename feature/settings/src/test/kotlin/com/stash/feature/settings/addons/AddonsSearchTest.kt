package com.stash.feature.settings.addons

import com.google.common.truth.Truth.assertThat
import com.stash.feature.settings.R
import com.stash.feature.settings.SettingsPage
import com.stash.feature.settings.searchSettings
import com.stash.feature.settings.settingsSearchIndex
import org.junit.Test

/** Settings search finds the Add-ons page (add-ons plan phase 4 §2.1), by its rows' names and by the words people use. */
class AddonsSearchTest {

    private val english = mapOf(
        R.string.settings_addons_title to "Add-ons",
        R.string.settings_addons_order_section to "Play order",
        R.string.settings_addons_link_title to "Add from a link",
        R.string.settings_lossless_sources_title to "Lossless sources",
        R.string.settings_web_link_nav_title to "Link Stash on the web",
    )
    private val index = settingsSearchIndex { id -> english[id] ?: "zz$id" }
    private val pages = SettingsPage.entries.associateWith { it.name.lowercase() } + (SettingsPage.ADDONS to "Add-ons")

    private fun search(q: String) = searchSettings(q, index) { pages.getValue(it) }

    @Test fun `the page and its two parts are found by name`() {
        assertThat(search("add-ons").first().page).isEqualTo(SettingsPage.ADDONS)
        assertThat(search("addons").map { it.page }).contains(SettingsPage.ADDONS)
        assertThat(search("play order").first().title).isEqualTo("Play order")
        assertThat(search("add from a link").first().title).isEqualTo("Add from a link")
    }

    @Test fun `the words people use lead there`() {
        for (word in listOf("plugin", "extension", "eclipse", "bitchord", "tidal server", "priority", "paste", "scan")) {
            assertThat(search(word).map { it.page }).contains(SettingsPage.ADDONS)
        }
    }

    @Test fun `the old Advanced row is gone from search, and qr still means the web link alone`() {
        // The Custom Tidal server field left Lossless sources › Advanced: its words lead to Add-ons now.
        assertThat(search("tidal server").map { it.page }).doesNotContain(SettingsPage.LOSSLESS_SOURCES)
        assertThat(search("qr").map { it.title }).containsExactly("Link Stash on the web")
    }
}
