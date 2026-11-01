package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the sections added after "From your record" land ([ADDED_SECTION_ANCHORS]): in a fresh install's order and
 * in an order saved before they existed, which is read with them in place and never rewritten.
 */
class HomeAddedSectionsTest {

    /** Every section of v0.9.122, as a user who reordered Home once might have saved them. */
    private val savedBy0_9_122 = listOf(
        "discover", "your_record", "your_listening", "your_playlists", "record_mixes", "new_releases",
        "qobuz_playlists", "top_albums", "made_for_you", "radios", "mood_decades", "community",
    )

    @Test
    fun `a fresh install has new from your artists right above new releases`() {
        val order = resolveHomeSectionOrder(emptyList())
        assertEquals(order.indexOf(HomeSection.NEW_RELEASES) - 1, order.indexOf(HomeSection.NEW_FROM_ARTISTS))
        assertTrue(order.indexOf(HomeSection.YOUR_PLAYLISTS) < order.indexOf(HomeSection.NEW_FROM_ARTISTS))
        assertTrue(HomeSection.NEW_FROM_ARTISTS in DEFAULT_HOME_SECTIONS)
        assertEquals(HomeSection.entries.size, order.toSet().size)
    }

    @Test
    fun `an order saved before it gets it above the catalogs, everything else as saved`() {
        val resolved = resolveHomeSectionOrder(savedBy0_9_122)
        val savedSections = savedBy0_9_122.mapNotNull(HomeSection::fromKey)
        val at = savedSections.indexOf(HomeSection.NEW_RELEASES)
        assertEquals(savedSections.take(at) + HomeSection.NEW_FROM_ARTISTS + savedSections.drop(at), resolved)
        // the same on a reordered Home and before the reorder (Your listening was already placed)
        assertEquals(resolved, effectiveHomeSectionOrder(savedBy0_9_122, promoted = true))
    }

    @Test
    fun `with the catalogs moved, it goes above the first of them`() {
        val saved = listOf("discover", "made_for_you", "top_albums", "radios", "new_releases", "qobuz_playlists")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(resolved.indexOf(HomeSection.TOP_ALBUMS) - 1, resolved.indexOf(HomeSection.NEW_FROM_ARTISTS))
    }

    @Test
    fun `once in a saved order it stays where the user put it`() {
        val saved = savedBy0_9_122 + "new_from_artists"
        assertEquals(HomeSection.NEW_FROM_ARTISTS, resolveHomeSectionOrder(saved).last())
        val first = listOf("new_from_artists") + savedBy0_9_122
        assertEquals(HomeSection.NEW_FROM_ARTISTS, resolveHomeSectionOrder(first).first())
    }

    @Test
    fun `it can be hidden like any section, keeping its place`() {
        val order = effectiveHomeSectionOrder(savedBy0_9_122, promoted = true)
        assertFalse(HomeSection.NEW_FROM_ARTISTS in visibleHomeSections(order, setOf(HomeSection.NEW_FROM_ARTISTS), communityOn = false))
        assertTrue(HomeSection.NEW_FROM_ARTISTS in visibleHomeSections(order, emptySet(), communityOn = false))
        assertEquals(HomeSection.NEW_FROM_ARTISTS, HomeSection.fromKey("new_from_artists"))
    }

    @Test
    fun `placing is idempotent and keeps a full permutation`() {
        for (saved in listOf(emptyList(), savedBy0_9_122, listOf("radios"), listOf("top_albums", "discover"))) {
            val resolved = resolveHomeSectionOrder(saved)
            assertEquals(HomeSection.entries.toSet(), resolved.toSet())
            assertEquals(HomeSection.entries.size, resolved.size)
            // Read back after a save (any reorder writes the whole order), it is the same order
            assertEquals(resolved, resolveHomeSectionOrder(resolved.map { it.key }))
        }
    }
}
