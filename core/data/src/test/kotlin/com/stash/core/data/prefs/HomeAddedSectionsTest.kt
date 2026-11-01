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
    fun `a fresh install has new from your artists, then moods and moments, right above new releases`() {
        val order = resolveHomeSectionOrder(emptyList())
        assertEquals(order.indexOf(HomeSection.NEW_RELEASES) - 2, order.indexOf(HomeSection.NEW_FROM_ARTISTS))
        assertEquals(order.indexOf(HomeSection.NEW_RELEASES) - 1, order.indexOf(HomeSection.MOODS))
        assertTrue(HomeSection.MOODS in DEFAULT_HOME_SECTIONS)
        assertTrue(order.indexOf(HomeSection.YOUR_PLAYLISTS) < order.indexOf(HomeSection.NEW_FROM_ARTISTS))
        assertTrue(HomeSection.NEW_FROM_ARTISTS in DEFAULT_HOME_SECTIONS)
        assertEquals(HomeSection.entries.size, order.toSet().size)
    }

    @Test
    fun `an order saved before it gets it above the catalogs, everything else as saved`() {
        val resolved = resolveHomeSectionOrder(savedBy0_9_122)
        val savedSections = savedBy0_9_122.mapNotNull(HomeSection::fromKey)
        val at = savedSections.indexOf(HomeSection.NEW_RELEASES)
        assertEquals(
            savedSections.take(at) + HomeSection.BECAUSE_YOU_LIKE + HomeSection.NEW_FROM_ARTISTS + HomeSection.MOODS + savedSections.drop(at),
            resolved,
        )
        // the same on a reordered Home and before the reorder (Your listening was already placed)
        assertEquals(resolved, effectiveHomeSectionOrder(savedBy0_9_122, promoted = true))
    }

    @Test
    fun `with the catalogs moved, it goes above the first of them`() {
        val saved = listOf("discover", "made_for_you", "top_albums", "radios", "new_releases", "qobuz_playlists")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(resolved.indexOf(HomeSection.TOP_ALBUMS) - 2, resolved.indexOf(HomeSection.NEW_FROM_ARTISTS))
        assertEquals(resolved.indexOf(HomeSection.TOP_ALBUMS) - 1, resolved.indexOf(HomeSection.MOODS))
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
    fun `with new releases hidden, it still sits where new releases would be and shows`() {
        // Hidden sections keep their place in the order: the anchor is still there, only not shown
        val order = effectiveHomeSectionOrder(savedBy0_9_122, promoted = true)
        assertEquals(order.indexOf(HomeSection.NEW_RELEASES) - 2, order.indexOf(HomeSection.NEW_FROM_ARTISTS))
        assertEquals(order.indexOf(HomeSection.NEW_RELEASES) - 1, order.indexOf(HomeSection.MOODS))
        val shown = visibleHomeSections(order, setOf(HomeSection.NEW_RELEASES), communityOn = false)
        assertFalse(HomeSection.NEW_RELEASES in shown)
        assertEquals(shown.indexOf(HomeSection.QOBUZ_PLAYLISTS) - 2, shown.indexOf(HomeSection.NEW_FROM_ARTISTS))
        assertEquals(shown.indexOf(HomeSection.QOBUZ_PLAYLISTS) - 1, shown.indexOf(HomeSection.MOODS))
    }

    @Test
    fun `an order saved with new from your artists gets moods and moments under it, wherever the user put it`() {
        // Saved by the release that added New from your artists, after the user moved it to the end
        val saved = savedBy0_9_122 + "new_from_artists"
        val resolved = resolveHomeSectionOrder(saved)
        // Moods & moments doesn't follow it there: it takes its own place, above the catalogs
        assertEquals(HomeSection.NEW_FROM_ARTISTS, resolved.last())
        assertEquals(resolved.indexOf(HomeSection.NEW_RELEASES) - 1, resolved.indexOf(HomeSection.MOODS))
        assertEquals(HomeSection.MOODS, HomeSection.fromKey("moods"))
        assertFalse(HomeSection.MOODS in visibleHomeSections(resolved, setOf(HomeSection.MOODS), communityOn = false))
    }

    @Test
    fun `because you like leads the new shelves on a fresh install, under the record's mixes`() {
        val order = resolveHomeSectionOrder(emptyList())
        assertEquals(order.indexOf(HomeSection.NEW_FROM_ARTISTS) - 1, order.indexOf(HomeSection.BECAUSE_YOU_LIKE))
        assertEquals(order.indexOf(HomeSection.RECORD_MIXES) + 1, order.indexOf(HomeSection.BECAUSE_YOU_LIKE))
        assertTrue(HomeSection.BECAUSE_YOU_LIKE in DEFAULT_HOME_SECTIONS)
        assertEquals(HomeSection.BECAUSE_YOU_LIKE, HomeSection.fromKey("because_you_like"))
    }

    @Test
    fun `an order saved with the other new shelves gets because you like above the first of them`() {
        // Saved by a release with New from your artists and Moods & moments, the user having moved Moods up top
        val saved = listOf("discover", "moods", "your_record", "your_listening", "your_playlists", "record_mixes", "new_from_artists") +
            savedBy0_9_122.drop(5)
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.BECAUSE_YOU_LIKE, HomeSection.MOODS), resolved.take(3))
        assertEquals(saved.mapNotNull(HomeSection::fromKey), resolved - HomeSection.BECAUSE_YOU_LIKE)
        // with them where they were put by default: right above New from your artists
        val asDefault = savedBy0_9_122.take(5) + listOf("new_from_artists", "moods") + savedBy0_9_122.drop(5)
        val placed = resolveHomeSectionOrder(asDefault)
        assertEquals(placed.indexOf(HomeSection.NEW_FROM_ARTISTS) - 1, placed.indexOf(HomeSection.BECAUSE_YOU_LIKE))
        assertFalse(HomeSection.BECAUSE_YOU_LIKE in visibleHomeSections(placed, setOf(HomeSection.BECAUSE_YOU_LIKE), communityOn = false))
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
