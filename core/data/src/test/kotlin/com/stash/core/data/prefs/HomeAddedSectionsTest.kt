package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(
            savedSections.take(at) + HomeSection.BECAUSE_YOU_LIKE + HomeSection.NEW_FROM_ARTISTS + savedSections.drop(at),
            resolved,
        )
        // Home shows the same, with the record's mixes under the card (they follow it wherever it is), on a reordered Home and
        // before the reorder (Your listening was already placed)
        val home = listOf(
            HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING,
            HomeSection.YOUR_PLAYLISTS, HomeSection.BECAUSE_YOU_LIKE, HomeSection.NEW_FROM_ARTISTS,
        ) + savedSections.drop(at)
        assertEquals(home, effectiveHomeSectionOrder(savedBy0_9_122, promoted = true))
        assertEquals(home, effectiveHomeSectionOrder(savedBy0_9_122, promoted = false))
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
        // New from your artists keeps the place the user gave it; Because you like, new to this order, would go
        // above it, but never above Discover: it lands directly under Discover instead
        assertEquals(
            listOf(HomeSection.NEW_FROM_ARTISTS, HomeSection.DISCOVER, HomeSection.BECAUSE_YOU_LIKE),
            resolveHomeSectionOrder(first).take(3),
        )
        assertEquals(HomeSection.NEW_FROM_ARTISTS, resolveHomeSectionOrder(listOf("because_you_like") + first).take(2).last())
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
        assertEquals(order.indexOf(HomeSection.NEW_RELEASES) - 1, order.indexOf(HomeSection.NEW_FROM_ARTISTS))
        val shown = visibleHomeSections(order, setOf(HomeSection.NEW_RELEASES), communityOn = false)
        assertFalse(HomeSection.NEW_RELEASES in shown)
        assertEquals(shown.indexOf(HomeSection.QOBUZ_PLAYLISTS) - 1, shown.indexOf(HomeSection.NEW_FROM_ARTISTS))
    }

    @Test
    fun `moods and moments, a Home section only unreleased builds had, drops out of a saved order and a hidden set`() {
        // It moved to the Search tab before it shipped: an order or a hidden set a test build saved still names it
        assertNull(HomeSection.fromKey("moods"))
        val saved = listOf("discover", "moods", "your_record") + savedBy0_9_122.drop(2) + listOf("new_from_artists", "because_you_like")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(saved.filter { it != "moods" }.mapNotNull(HomeSection::fromKey), resolved)
        assertEquals(HomeSection.entries.toSet(), resolved.toSet())
        // Read back after the next save, which writes only the sections that exist: the same order, without it
        assertEquals(resolved, resolveHomeSectionOrder(resolved.map { it.key }))
        assertTrue(resolved.none { it.key == "moods" })
    }

    @Test
    fun `because you like leads the new shelves on a fresh install, under your playlists`() {
        val order = resolveHomeSectionOrder(emptyList())
        assertEquals(order.indexOf(HomeSection.NEW_FROM_ARTISTS) - 1, order.indexOf(HomeSection.BECAUSE_YOU_LIKE))
        // The record's mixes sit directly under the card (owner 2026-11-01), so Your playlists is the section above the new ones
        assertEquals(order.indexOf(HomeSection.YOUR_RECORD) + 1, order.indexOf(HomeSection.RECORD_MIXES))
        assertEquals(order.indexOf(HomeSection.YOUR_PLAYLISTS) + 1, order.indexOf(HomeSection.BECAUSE_YOU_LIKE))
        assertTrue(HomeSection.BECAUSE_YOU_LIKE in DEFAULT_HOME_SECTIONS)
        assertEquals(HomeSection.BECAUSE_YOU_LIKE, HomeSection.fromKey("because_you_like"))
    }

    @Test
    fun `an order saved with new from your artists gets because you like above it`() {
        // Saved by a release with New from your artists, the user having moved it up top
        val saved = listOf("discover", "new_from_artists", "your_record", "your_listening", "your_playlists", "record_mixes") +
            savedBy0_9_122.drop(5)
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.BECAUSE_YOU_LIKE, HomeSection.NEW_FROM_ARTISTS), resolved.take(3))
        assertEquals(saved.mapNotNull(HomeSection::fromKey), resolved - HomeSection.BECAUSE_YOU_LIKE)
        // with it where it was put by default: right above New releases
        val asDefault = savedBy0_9_122.take(5) + listOf("new_from_artists") + savedBy0_9_122.drop(5)
        val placed = resolveHomeSectionOrder(asDefault)
        assertEquals(placed.indexOf(HomeSection.NEW_FROM_ARTISTS) - 1, placed.indexOf(HomeSection.BECAUSE_YOU_LIKE))
        assertFalse(HomeSection.BECAUSE_YOU_LIKE in visibleHomeSections(placed, setOf(HomeSection.BECAUSE_YOU_LIKE), communityOn = false))
    }

    @Test
    fun `a new section never goes above discover, which keeps its lead`() {
        // The user moved the catalogs over Discover: the new shelves land directly under it, not above it
        val catalogsFirst = listOf("new_releases", "qobuz_playlists", "top_albums", "discover") +
            savedBy0_9_122.filter { it !in setOf("new_releases", "qobuz_playlists", "top_albums", "discover") }
        val resolved = resolveHomeSectionOrder(catalogsFirst)
        val discover = resolved.indexOf(HomeSection.DISCOVER)
        assertEquals(3, discover)
        for (added in listOf(HomeSection.BECAUSE_YOU_LIKE, HomeSection.NEW_FROM_ARTISTS)) {
            assertTrue("$added above Discover in $resolved", resolved.indexOf(added) > discover)
        }
        // Because you like above the shelf it's anchored to
        assertEquals(
            listOf(HomeSection.BECAUSE_YOU_LIKE, HomeSection.NEW_FROM_ARTISTS),
            resolved.subList(discover + 1, discover + 3),
        )
        // Discover first (every default order): it stays first
        assertEquals(HomeSection.DISCOVER, resolveHomeSectionOrder(emptyList()).first())
        assertEquals(HomeSection.DISCOVER, resolveHomeSectionOrder(savedBy0_9_122).first())
    }

    @Test
    fun `on Home the record's mixes stay under the card and a new section stays under discover, both at once`() {
        val added = listOf(HomeSection.BECAUSE_YOU_LIKE, HomeSection.NEW_FROM_ARTISTS)
        val catalogsFirst = listOf("new_releases", "qobuz_playlists", "top_albums", "discover") +
            savedBy0_9_122.filter { it !in setOf("new_releases", "qobuz_playlists", "top_albums", "discover") }
        val saves = listOf(
            emptyList(),
            savedBy0_9_122,
            catalogsFirst,
            // the mixes moved over Discover and the catalogs, the card well below
            listOf("record_mixes", "top_albums", "discover", "radios", "your_record"),
            // the card over Discover, its mixes missing, a catalog over Discover too
            listOf("your_record", "new_releases", "discover", "radios"),
        )
        for (saved in saves) {
            for (promoted in listOf(true, false)) {
                val home = effectiveHomeSectionOrder(saved, promoted)
                val discover = home.indexOf(HomeSection.DISCOVER)
                assertEquals("$saved, promoted $promoted", HomeSection.RECORD_MIXES, home[home.indexOf(HomeSection.YOUR_RECORD) + 1])
                for (section in added) {
                    assertTrue("$section above Discover in $home", home.indexOf(section) > discover)
                }
                assertEquals("$saved, promoted $promoted", HomeSection.entries.size, home.toSet().size)
            }
        }
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
