package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/** Merge semantics for the saved Home section order (see [resolveHomeSectionOrder]). */
class HomeSectionOrderTest {

    @Test
    fun `empty saved order yields the default layout`() {
        assertEquals(HomeSection.entries.toList(), resolveHomeSectionOrder(emptyList()))
    }

    @Test
    fun `saved permutation is honored`() {
        val saved = listOf(
            "made_for_you", "radios", "mood_decades",
            "new_releases", "qobuz_playlists", "top_albums",
        )
        assertEquals(
            listOf(
                HomeSection.MADE_FOR_YOU, HomeSection.RADIOS, HomeSection.MOOD_DECADES,
                HomeSection.NEW_RELEASES, HomeSection.QOBUZ_PLAYLISTS, HomeSection.TOP_ALBUMS,
            ),
            resolveHomeSectionOrder(saved),
        )
    }

    @Test
    fun `unknown keys are dropped and missing sections appended in default order`() {
        val saved = listOf("radios", "retired_section", "top_albums")
        assertEquals(
            listOf(
                HomeSection.RADIOS, HomeSection.TOP_ALBUMS,
                // appended in default order:
                HomeSection.NEW_RELEASES, HomeSection.QOBUZ_PLAYLISTS,
                HomeSection.MADE_FOR_YOU, HomeSection.MOOD_DECADES,
            ),
            resolveHomeSectionOrder(saved),
        )
    }

    @Test
    fun `duplicate keys keep first occurrence`() {
        val saved = listOf("radios", "radios", "new_releases")
        val result = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.entries.size, result.size)
        assertEquals(HomeSection.RADIOS, result[0])
        assertEquals(HomeSection.NEW_RELEASES, result[1])
    }
}
