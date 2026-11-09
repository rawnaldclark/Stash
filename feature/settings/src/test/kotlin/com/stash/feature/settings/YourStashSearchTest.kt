package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Settings search finds Your Stash and its rows in the real index ([settingsSearchIndex]), the old name still leads there,
 * and the hub drops them when the link is switched off ([searchableIndex]). Strings come from a fake lookup: the names that
 * matter here in English, every other setting a placeholder no query below can match.
 */
class YourStashSearchTest {

    private val english = mapOf(
        R.string.settings_your_stash_title to "Your Stash",
        R.string.settings_web_link_nav_title to "Link Stash on the web",
        R.string.settings_your_stash_section_devices to "Devices",
        R.string.settings_web_link_handoff_title to "Pick up where you left off",
        R.string.settings_mirror_section_title to "Mirror between your devices",
        R.string.settings_web_link_send_library_title to "Send my library to a browser",
        R.string.settings_your_stash_section_supporter to "Supporter",
        R.string.settings_your_stash_section_coming to "What's coming",
        R.string.settings_web_link_action_unlink_everything to "Unlink everything",
        R.string.settings_supporter_offer to "Unlimited Tidal for supporters",
    )
    private val index = settingsSearchIndex { id -> english[id] ?: "zz$id" }

    private val pages = SettingsPage.entries.associateWith { it.name.lowercase() } + mapOf(
        SettingsPage.LIBRARY to "Library & Storage",
        SettingsPage.YOUR_STASH to "Library & Storage › Your Stash",
        SettingsPage.LOSSLESS_SOURCES to "Audio & Quality › Lossless sources",
    )

    private fun search(q: String, idx: List<SettingsSearchEntry> = index) = searchSettings(q, idx) { pages.getValue(it) }

    @Test fun `Your Stash is in the index on its own page`() {
        val hit = search("your stash").first()
        assertThat(hit.title).isEqualTo("Your Stash")
        assertThat(hit.page).isEqualTo(SettingsPage.YOUR_STASH)
    }

    @Test fun `someone looking for an account finds Your Stash`() {
        assertThat(search("account").map { it.title }).contains("Your Stash")
    }

    @Test fun `the old name still finds the link, now on Your Stash`() {
        val hit = search("link stash on the web").first()
        assertThat(hit.title).isEqualTo("Link Stash on the web")
        assertThat(hit.page).isEqualTo(SettingsPage.YOUR_STASH)
        // Nothing points at the old place any more.
        assertThat(index.filter { it.page == SettingsPage.LIBRARY }.map { it.title }).doesNotContain("Link Stash on the web")
    }

    @Test fun `scanning words find the link`() {
        assertThat(search("qr").map { it.title }).containsExactly("Link Stash on the web")
        assertThat(search("web player").map { it.page }.toSet()).containsExactly(SettingsPage.YOUR_STASH)
    }

    @Test fun `the page's rows are findable by their names and their synonyms`() {
        assertThat(search("handoff").map { it.title }).containsExactly("Pick up where you left off")
        assertThat(search("mirror").map { it.title }).contains("Mirror between your devices")
        assertThat(search("passkey").map { it.title }).containsExactly("What's coming")
        assertThat(search("new phone").map { it.title }).containsExactly("What's coming")
        assertThat(search("unlink").map { it.title }).containsExactly("Unlink everything")
        assertThat(search("rename").map { it.title }).containsExactly("Devices")
    }

    @Test fun `supporter finds both the claim on Lossless sources and the row on Your Stash`() {
        assertThat(search("kofi").map { it.page }).containsAtLeast(SettingsPage.LOSSLESS_SOURCES, SettingsPage.YOUR_STASH)
    }

    @Test fun `with the link switched off, Your Stash leaves the index and nothing else does`() {
        val off = searchableIndex(index, yourStash = false)
        assertThat(off.none { it.page == SettingsPage.YOUR_STASH }).isTrue()
        assertThat(off).containsExactlyElementsIn(index.filter { it.page != SettingsPage.YOUR_STASH }).inOrder()
        assertThat(search("your stash", off)).isEmpty()
        assertThat(searchableIndex(index, yourStash = true)).isEqualTo(index)
    }
}
