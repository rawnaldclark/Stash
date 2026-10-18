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
        assertEquals(HomeSection.RADIOS, result[0])
        assertEquals(HomeSection.NEW_RELEASES, result[1])
    }

    @Test
    fun `your playlists leads the default order`() {
        assertEquals(HomeSection.YOUR_PLAYLISTS, resolveHomeSectionOrder(emptyList()).first())
    }

    @Test
    fun `saved order without your playlists appends it, preserving the arrangement`() {
        val saved = listOf("made_for_you", "new_releases")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.MADE_FOR_YOU, resolved[0])
        assertEquals(HomeSection.NEW_RELEASES, resolved[1])
        // New-in-an-update section can't be hidden by a stale pref: appended.
        assertTrue(HomeSection.YOUR_PLAYLISTS in resolved)
        assertTrue(resolved.indexOf(HomeSection.YOUR_PLAYLISTS) >= 2)
    }

    @Test
    fun `community is off by default, including right after the update that adds it`() {
        assertFalse(HomeSection.COMMUNITY in visibleHomeSections(resolveHomeSectionOrder(emptyList()), emptySet(), communityOn = false))
        val savedBeforeTheUpdate = listOf("your_playlists", "new_releases", "qobuz_playlists", "top_albums", "made_for_you", "radios", "mood_decades")
        assertEquals(
            savedBeforeTheUpdate.mapNotNull(HomeSection::fromKey) + HomeSection.YOUR_LISTENING,
            visibleHomeSections(resolveHomeSectionOrder(savedBeforeTheUpdate), emptySet(), communityOn = false),
        )
        // Home before the preference loads, and the preference's fallback when it can't be read.
        assertFalse(HomeSection.COMMUNITY in DEFAULT_HOME_SECTIONS)
    }

    @Test
    fun `turned on, community shows where it sits in the order`() {
        val order = withCommunityFirst(resolveHomeSectionOrder(emptyList()))
        assertEquals(HomeSection.COMMUNITY, visibleHomeSections(order, emptySet(), communityOn = true).first())
        assertEquals(order - HomeSection.COMMUNITY, visibleHomeSections(order, emptySet(), communityOn = false))
    }

    @Test
    fun `withCommunityFirst moves community to the top and keeps the rest in order`() {
        val order = resolveHomeSectionOrder(listOf("radios", "top_albums"))
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
        assertEquals(saved.mapNotNull(HomeSection::fromKey), resolved.dropLast(1))
        assertEquals(HomeSection.YOUR_LISTENING, resolved.last())
        // New in the update, it shows (Home itself waits for the first listen).
        assertTrue(HomeSection.YOUR_LISTENING in visibleHomeSections(resolved, emptySet(), communityOn = true))
        assertTrue(HomeSection.YOUR_LISTENING in DEFAULT_HOME_SECTIONS)
    }

    @Test
    fun `your listening can be moved and hidden like any section`() {
        val saved = listOf("your_listening", "radios")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.YOUR_LISTENING, resolved.first())
        assertFalse(HomeSection.YOUR_LISTENING in visibleHomeSections(resolved, setOf(HomeSection.YOUR_LISTENING), communityOn = false))
    }
}
