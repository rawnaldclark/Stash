package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                // Discover was pinned on top before it was a section: it goes to the front.
                HomeSection.DISCOVER,
                HomeSection.MADE_FOR_YOU, HomeSection.RADIOS, HomeSection.MOOD_DECADES,
                HomeSection.NEW_RELEASES, HomeSection.QOBUZ_PLAYLISTS, HomeSection.TOP_ALBUMS,
                // appended: added after this arrangement was saved.
                HomeSection.YOUR_PLAYLISTS, HomeSection.COMMUNITY, HomeSection.YOUR_LISTENING,
            ),
            resolveHomeSectionOrder(saved),
        )
    }

    @Test
    fun `unknown keys are dropped and missing sections appended in default order`() {
        val saved = listOf("radios", "retired_section", "top_albums")
        assertEquals(
            listOf(
                HomeSection.DISCOVER,
                HomeSection.RADIOS, HomeSection.TOP_ALBUMS,
                // appended in default order:
                HomeSection.YOUR_PLAYLISTS, HomeSection.NEW_RELEASES,
                HomeSection.QOBUZ_PLAYLISTS, HomeSection.MADE_FOR_YOU,
                HomeSection.MOOD_DECADES, HomeSection.COMMUNITY, HomeSection.YOUR_LISTENING,
            ),
            resolveHomeSectionOrder(saved),
        )
    }

    @Test
    fun `duplicate keys keep first occurrence`() {
        val saved = listOf("radios", "radios", "new_releases")
        val result = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.entries.size, result.size)
        assertEquals(HomeSection.DISCOVER, result[0])
        assertEquals(HomeSection.RADIOS, result[1])
        assertEquals(HomeSection.NEW_RELEASES, result[2])
    }

    @Test
    fun `discover leads the default order, then your playlists`() {
        val resolved = resolveHomeSectionOrder(emptyList())
        assertEquals(HomeSection.DISCOVER, resolved[0])
        assertEquals(HomeSection.YOUR_PLAYLISTS, resolved[1])
    }

    @Test
    fun `a saved order from before discover existed puts discover at the front`() {
        val saved = listOf("radios", "new_releases")
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.RADIOS, HomeSection.NEW_RELEASES),
            resolveHomeSectionOrder(saved).take(3),
        )
    }

    @Test
    fun `a saved order that already has discover keeps its position`() {
        val resolved = resolveHomeSectionOrder(listOf("radios", "discover", "new_releases"))
        assertEquals(HomeSection.RADIOS, resolved[0])
        assertEquals(HomeSection.DISCOVER, resolved[1])
    }

    @Test
    fun `saved order without your playlists appends it, preserving the arrangement`() {
        val saved = listOf("made_for_you", "new_releases")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.DISCOVER, resolved[0])
        assertEquals(HomeSection.MADE_FOR_YOU, resolved[1])
        assertEquals(HomeSection.NEW_RELEASES, resolved[2])
        // New-in-an-update section can't be hidden by a stale pref: appended.
        assertTrue(HomeSection.YOUR_PLAYLISTS in resolved)
        assertTrue(resolved.indexOf(HomeSection.YOUR_PLAYLISTS) >= 3)
    }

    @Test
    fun `community is off by default, including right after the update that adds it`() {
        assertFalse(HomeSection.COMMUNITY in visibleHomeSections(effectiveHomeSectionOrder(emptyList(), false), emptySet(), communityOn = false))
        // Saved before the update: Discover then Your listening lead (not promoted yet), the rest keep the saved order.
        val savedBeforeTheUpdate = listOf("your_playlists", "new_releases", "qobuz_playlists", "top_albums", "made_for_you", "radios", "mood_decades")
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_LISTENING) + savedBeforeTheUpdate.mapNotNull(HomeSection::fromKey),
            visibleHomeSections(effectiveHomeSectionOrder(savedBeforeTheUpdate, promoted = false), emptySet(), communityOn = false),
        )
        // Home before the preference loads, and the preference's fallback when it can't be read.
        assertFalse(HomeSection.COMMUNITY in DEFAULT_HOME_SECTIONS)
    }

    @Test
    fun `turned on, community shows where it sits in the order`() {
        val order = withCommunityFirst(resolveHomeSectionOrder(emptyList()))
        assertEquals(HomeSection.DISCOVER, order.first())
        assertEquals(HomeSection.COMMUNITY, visibleHomeSections(order, emptySet(), communityOn = true)[1])
        assertEquals(order - HomeSection.COMMUNITY, visibleHomeSections(order, emptySet(), communityOn = false))
    }

    @Test
    fun `withCommunityFirst lands community directly under discover, rest in order`() {
        val order = resolveHomeSectionOrder(listOf("radios", "top_albums"))
        val moved = withCommunityFirst(order)
        assertEquals(HomeSection.DISCOVER, moved[0])
        assertEquals(HomeSection.COMMUNITY, moved[1])
        assertEquals(order - HomeSection.COMMUNITY, moved - HomeSection.COMMUNITY)
    }

    @Test
    fun `withCommunityFirst puts community first when discover has been moved down`() {
        val order = listOf(HomeSection.RADIOS, HomeSection.DISCOVER) +
            (HomeSection.entries - HomeSection.RADIOS - HomeSection.DISCOVER)
        val moved = withCommunityFirst(order)
        assertEquals(HomeSection.COMMUNITY, moved.first())
        assertEquals(order - HomeSection.COMMUNITY, moved.drop(1))
    }

    @Test
    fun `your listening is appended under an existing arrangement, after community`() {
        // A layout saved before the update that adds "Your listening" (Community already in it).
        val saved = listOf(
            "community", "made_for_you", "your_playlists", "radios", "new_releases",
            "qobuz_playlists", "top_albums", "mood_decades",
        )
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.DISCOVER, resolved.first())
        assertEquals(saved.mapNotNull(HomeSection::fromKey), resolved.drop(1).dropLast(1))
        assertEquals(HomeSection.YOUR_LISTENING, resolved.last())
        // New in the update, it shows (Home itself waits for the first listen).
        assertTrue(HomeSection.YOUR_LISTENING in visibleHomeSections(resolved, emptySet(), communityOn = true))
        assertTrue(HomeSection.YOUR_LISTENING in DEFAULT_HOME_SECTIONS)
        // Home shows it directly under Discover until the user reorders (effectiveHomeSectionOrder).
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_LISTENING),
            effectiveHomeSectionOrder(saved, promoted = false).take(2),
        )
    }

    @Test
    fun `before a reorder your listening leads under discover, whatever the saved order says`() {
        val saved = listOf("radios", "top_albums", "your_listening")
        val effective = effectiveHomeSectionOrder(saved, promoted = false)
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_LISTENING), effective.take(2))
        assertEquals(HomeSection.entries.size, effective.size)
        assertEquals(
            resolveHomeSectionOrder(saved) - HomeSection.DISCOVER - HomeSection.YOUR_LISTENING,
            effective.drop(2),
        )
    }

    @Test
    fun `after a reorder the saved order is used as it is`() {
        val saved = listOf("radios", "your_listening", "top_albums")
        assertEquals(resolveHomeSectionOrder(saved), effectiveHomeSectionOrder(saved, promoted = true))
    }

    @Test
    fun `a fresh install puts discover then your listening first, and community stays off`() {
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_LISTENING), DEFAULT_HOME_SECTIONS.take(2))
        assertEquals(HomeSection.entries.size - 1, DEFAULT_HOME_SECTIONS.size)
        assertFalse(HomeSection.COMMUNITY in DEFAULT_HOME_SECTIONS)
    }

    @Test
    fun `effective unpromoted order is discover, your listening, then the rest`() {
        val order = effectiveHomeSectionOrder(emptyList(), promoted = false)
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_LISTENING), order.take(2))
        assertEquals(
            resolveHomeSectionOrder(emptyList()) - HomeSection.DISCOVER - HomeSection.YOUR_LISTENING,
            order.drop(2),
        )
    }

    @Test
    fun `discover moved down keeps your listening first while the promotion is still on`() {
        // Discover moved down by hand, but Home has never been reordered (promotion not yet ended).
        val saved = listOf("radios", "discover", "new_releases")
        val resolved = resolveHomeSectionOrder(saved)
        val effective = effectiveHomeSectionOrder(saved, promoted = false)
        assertEquals(HomeSection.YOUR_LISTENING, effective.first())
        assertEquals(resolved - HomeSection.YOUR_LISTENING, effective.drop(1))
    }

    @Test
    fun `hiding discover removes it from the visible sections`() {
        val order = effectiveHomeSectionOrder(emptyList(), promoted = false)
        assertTrue(HomeSection.DISCOVER in visibleHomeSections(order, emptySet(), communityOn = false))
        assertFalse(HomeSection.DISCOVER in visibleHomeSections(order, setOf(HomeSection.DISCOVER), communityOn = false))
    }

    @Test
    fun `your listening can be moved and hidden like any section`() {
        val saved = listOf("your_listening", "radios")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_LISTENING), resolved.take(2))
        assertFalse(HomeSection.YOUR_LISTENING in visibleHomeSections(resolved, setOf(HomeSection.YOUR_LISTENING), communityOn = false))
    }
}
