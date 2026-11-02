package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merge semantics for the saved Home section order (see [resolveHomeSectionOrder]). */
class HomeSectionOrderTest {

    @Test
    fun `empty saved order yields the default layout`() {
        // The enum's order, with Your record directly under Discover (as the web's card sits) and its mixes directly under it
        assertEquals(
            listOf(
                HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_PLAYLISTS,
                HomeSection.NEW_RELEASES, HomeSection.QOBUZ_PLAYLISTS, HomeSection.TOP_ALBUMS, HomeSection.MADE_FOR_YOU,
                HomeSection.RADIOS, HomeSection.MOOD_DECADES, HomeSection.COMMUNITY, HomeSection.YOUR_LISTENING,
            ),
            resolveHomeSectionOrder(emptyList()),
        )
        assertEquals(HomeSection.entries.size, resolveHomeSectionOrder(emptyList()).size)
    }

    @Test
    fun `saved permutation is honored`() {
        val saved = listOf(
            "made_for_you", "radios", "mood_decades",
            "new_releases", "qobuz_playlists", "top_albums",
        )
        assertEquals(
            listOf(
                // Discover was pinned on top before it was a section: it goes to the front, Your record and its mixes under it.
                HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES,
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
                HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES,
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
        assertEquals(HomeSection.YOUR_RECORD, result[1])
        assertEquals(HomeSection.RECORD_MIXES, result[2])
        assertEquals(HomeSection.RADIOS, result[3])
        assertEquals(HomeSection.NEW_RELEASES, result[4])
    }

    @Test
    fun `discover leads the default order, then your record and its mixes, then your playlists`() {
        val resolved = resolveHomeSectionOrder(emptyList())
        assertEquals(HomeSection.DISCOVER, resolved[0])
        assertEquals(HomeSection.YOUR_RECORD, resolved[1])
        assertEquals(HomeSection.RECORD_MIXES, resolved[2])
        assertEquals(HomeSection.YOUR_PLAYLISTS, resolved[3])
    }

    @Test
    fun `a saved order from before discover existed puts discover at the front`() {
        val saved = listOf("radios", "new_releases")
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.RADIOS, HomeSection.NEW_RELEASES),
            resolveHomeSectionOrder(saved).take(5),
        )
    }

    @Test
    fun `a saved order that already has discover keeps its position`() {
        val resolved = resolveHomeSectionOrder(listOf("radios", "discover", "new_releases"))
        // Discover isn't first, so Your record (new in an update) leads, its mixes under it
        assertEquals(HomeSection.YOUR_RECORD, resolved[0])
        assertEquals(HomeSection.RECORD_MIXES, resolved[1])
        assertEquals(HomeSection.RADIOS, resolved[2])
        assertEquals(HomeSection.DISCOVER, resolved[3])
    }

    @Test
    fun `saved order without your playlists appends it, preserving the arrangement`() {
        val saved = listOf("made_for_you", "new_releases")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.DISCOVER, resolved[0])
        assertEquals(HomeSection.YOUR_RECORD, resolved[1])
        assertEquals(HomeSection.RECORD_MIXES, resolved[2])
        assertEquals(HomeSection.MADE_FOR_YOU, resolved[3])
        assertEquals(HomeSection.NEW_RELEASES, resolved[4])
        // New-in-an-update section can't be hidden by a stale pref: appended.
        assertTrue(HomeSection.YOUR_PLAYLISTS in resolved)
        assertTrue(resolved.indexOf(HomeSection.YOUR_PLAYLISTS) >= 5)
    }

    @Test
    fun `community is off by default, including right after the update that adds it`() {
        assertFalse(HomeSection.COMMUNITY in visibleHomeSections(effectiveHomeSectionOrder(emptyList(), false), emptySet(), communityOn = false))
        // Saved before the update: Discover, Your record (and its mixes) and Your listening lead (not promoted yet), the rest keep
        // the saved order.
        val savedBeforeTheUpdate = listOf("your_playlists", "new_releases", "qobuz_playlists", "top_albums", "made_for_you", "radios", "mood_decades")
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING) +
                savedBeforeTheUpdate.mapNotNull(HomeSection::fromKey),
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
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES), resolved.take(3))
        assertEquals(saved.mapNotNull(HomeSection::fromKey), resolved.drop(3).dropLast(1))
        assertEquals(HomeSection.YOUR_LISTENING, resolved.last())
        // New in the update, it shows (Home itself waits for the first listen).
        assertTrue(HomeSection.YOUR_LISTENING in visibleHomeSections(resolved, emptySet(), communityOn = true))
        assertTrue(HomeSection.YOUR_LISTENING in DEFAULT_HOME_SECTIONS)
        // Home shows it directly under Discover, Your record and its mixes until the user reorders (effectiveHomeSectionOrder).
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            effectiveHomeSectionOrder(saved, promoted = false).take(4),
        )
    }

    @Test
    fun `before a reorder your listening leads under discover, whatever the saved order says`() {
        val saved = listOf("radios", "top_albums", "your_listening")
        val effective = effectiveHomeSectionOrder(saved, promoted = false)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            effective.take(4),
        )
        assertEquals(HomeSection.entries.size, effective.size)
        assertEquals(
            resolveHomeSectionOrder(saved) - HomeSection.DISCOVER - HomeSection.YOUR_RECORD - HomeSection.RECORD_MIXES - HomeSection.YOUR_LISTENING,
            effective.drop(4),
        )
    }

    @Test
    fun `after a reorder the saved order is used as it is`() {
        val saved = listOf("radios", "your_listening", "top_albums")
        assertEquals(resolveHomeSectionOrder(saved), effectiveHomeSectionOrder(saved, promoted = true))
    }

    @Test
    fun `a fresh install puts discover, your record and its mixes, then your listening first, and community stays off`() {
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            DEFAULT_HOME_SECTIONS.take(4),
        )
        assertEquals(HomeSection.entries.size - 1, DEFAULT_HOME_SECTIONS.size)
        assertFalse(HomeSection.COMMUNITY in DEFAULT_HOME_SECTIONS)
    }

    @Test
    fun `effective unpromoted order is discover, your record and its mixes, your listening, then the rest`() {
        val order = effectiveHomeSectionOrder(emptyList(), promoted = false)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            order.take(4),
        )
        assertEquals(
            resolveHomeSectionOrder(emptyList()) - HomeSection.DISCOVER - HomeSection.YOUR_RECORD - HomeSection.RECORD_MIXES -
                HomeSection.YOUR_LISTENING,
            order.drop(4),
        )
    }

    @Test
    fun `discover moved down, your record then your listening lead while the promotion is still on`() {
        // Discover moved down by hand, but Home has never been reordered (promotion not yet ended).
        val saved = listOf("radios", "discover", "new_releases")
        val resolved = resolveHomeSectionOrder(saved)
        val effective = effectiveHomeSectionOrder(saved, promoted = false)
        assertEquals(listOf(HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING), effective.take(3))
        assertEquals(resolved - HomeSection.YOUR_RECORD - HomeSection.RECORD_MIXES - HomeSection.YOUR_LISTENING, effective.drop(3))
    }

    @Test
    fun `hiding discover removes it from the visible sections`() {
        val order = effectiveHomeSectionOrder(emptyList(), promoted = false)
        assertTrue(HomeSection.DISCOVER in visibleHomeSections(order, emptySet(), communityOn = false))
        assertFalse(HomeSection.DISCOVER in visibleHomeSections(order, setOf(HomeSection.DISCOVER), communityOn = false))
    }

    @Test
    fun `your record goes directly under discover in a saved order without it, and shows`() {
        // A layout saved before the update that adds "Your record" (every older section in it, Your listening moved up).
        val saved = listOf(
            "discover", "your_listening", "your_playlists", "new_releases", "qobuz_playlists", "top_albums",
            "made_for_you", "radios", "mood_decades", "community",
        )
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING, HomeSection.YOUR_PLAYLISTS) +
                saved.drop(3).mapNotNull(HomeSection::fromKey),
            resolved,
        )
        // a reordered Home (promotion over) keeps it there too
        assertEquals(resolved, effectiveHomeSectionOrder(saved, promoted = true))
        // where the user put it, it stays, its mixes with it
        val placed = listOf("discover", "radios", "your_record")
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.RADIOS, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES),
            resolveHomeSectionOrder(placed).take(4),
        )
        assertTrue(HomeSection.YOUR_RECORD in visibleHomeSections(resolved, emptySet(), communityOn = false))
        assertTrue(HomeSection.YOUR_RECORD in DEFAULT_HOME_SECTIONS)
        assertEquals(HomeSection.YOUR_RECORD, HomeSection.fromKey("your_record"))
        assertFalse(HomeSection.YOUR_RECORD in visibleHomeSections(resolved, setOf(HomeSection.YOUR_RECORD), communityOn = false))
    }

    @Test
    fun `your listening can be moved and hidden like any section`() {
        val saved = listOf("your_listening", "radios")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            resolved.take(4),
        )
        assertFalse(HomeSection.YOUR_LISTENING in visibleHomeSections(resolved, setOf(HomeSection.YOUR_LISTENING), communityOn = false))
    }

    @Test
    fun `a saved order without the mixes gets them directly under the card`() {
        // Every section of the release before them, as a user arranged them
        val saved = listOf(
            "discover", "your_record", "your_listening", "your_playlists", "new_releases", "qobuz_playlists", "top_albums",
            "made_for_you", "radios", "mood_decades", "community",
        )
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(saved.take(2).mapNotNull(HomeSection::fromKey) + HomeSection.RECORD_MIXES + saved.drop(2).mapNotNull(HomeSection::fromKey), resolved)
        assertEquals(resolved, effectiveHomeSectionOrder(saved, promoted = true))
        // the card moved down by hand: the mixes go under it there, not above the catalogs
        val moved = listOf("discover", "made_for_you", "top_albums", "radios", "your_record", "new_releases", "qobuz_playlists")
        assertEquals(
            listOf(
                HomeSection.DISCOVER, HomeSection.MADE_FOR_YOU, HomeSection.TOP_ALBUMS, HomeSection.RADIOS,
                HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.NEW_RELEASES,
            ),
            effectiveHomeSectionOrder(moved, promoted = true).take(7),
        )
        // shown by default
        assertTrue(HomeSection.RECORD_MIXES in DEFAULT_HOME_SECTIONS)
        assertEquals(HomeSection.RECORD_MIXES, HomeSection.fromKey("record_mixes"))
    }

    @Test
    fun `the mixes follow the card wherever the saved order put either`() {
        // v0.9.122 placed the mixes above the catalogs, and a user could move them anywhere; the card could be anywhere too.
        val saves = listOf(
            // as 0.9.122 left a pre-record arrangement: the mixes well below the card
            listOf("discover", "your_record", "community", "your_listening", "your_playlists", "record_mixes", "new_releases"),
            // the mixes moved above the card
            listOf("record_mixes", "discover", "radios", "your_record"),
            // the card at the bottom, the mixes at the top
            listOf("record_mixes") + (HomeSection.entries - HomeSection.RECORD_MIXES - HomeSection.YOUR_RECORD).map { it.key } + "your_record",
            // the mixes last, the card first
            listOf("your_record") + (HomeSection.entries - HomeSection.RECORD_MIXES - HomeSection.YOUR_RECORD).map { it.key } + "record_mixes",
        )
        for (saved in saves) {
            for (promoted in listOf(true, false)) {
                val effective = effectiveHomeSectionOrder(saved, promoted)
                val card = effective.indexOf(HomeSection.YOUR_RECORD)
                assertEquals("$saved, promoted $promoted", HomeSection.RECORD_MIXES, effective[card + 1])
                // a full permutation: nothing lost, nothing twice
                assertEquals("$saved, promoted $promoted", HomeSection.entries.toSet(), effective.toSet())
                assertEquals("$saved, promoted $promoted", HomeSection.entries.size, effective.size)
                // every other section keeps the saved order's sequence
                assertEquals(
                    "$saved, promoted $promoted",
                    resolveHomeSectionOrder(saved) - HomeSection.RECORD_MIXES - HomeSection.YOUR_LISTENING,
                    effective - HomeSection.RECORD_MIXES - HomeSection.YOUR_LISTENING,
                )
            }
        }
        // the card at the bottom: the mixes at the very end, under it
        assertEquals(
            listOf(HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES),
            effectiveHomeSectionOrder(saves[2], promoted = true).takeLast(2),
        )
    }

    @Test
    fun `the promotion never lands between the card and its mixes`() {
        // The mixes were moved above the card in 0.9.122: Your listening still goes under the pair, not into it.
        val saved = listOf("discover", "record_mixes", "your_record", "radios")
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING, HomeSection.RADIOS),
            effectiveHomeSectionOrder(saved, promoted = false).take(5),
        )
        // Your listening right under the card in the saved order: once promoted it stays there, under the pair
        val listeningUnderCard = listOf("discover", "your_record", "your_listening", "record_mixes", "radios")
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING, HomeSection.RADIOS),
            effectiveHomeSectionOrder(listeningUnderCard, promoted = true).take(5),
        )
    }

    @Test
    fun `moving the card moves its mixes with it`() {
        val order = effectiveHomeSectionOrder(emptyList(), promoted = true)
        // Discover, Your record, From your record, Your playlists, …
        val down = movedHomeSectionOrder(order, HomeSection.YOUR_RECORD, up = false)!!
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_PLAYLISTS, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.NEW_RELEASES),
            down.take(5),
        )
        val up = movedHomeSectionOrder(order, HomeSection.YOUR_RECORD, up = true)!!
        assertEquals(
            listOf(HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.DISCOVER, HomeSection.YOUR_PLAYLISTS),
            up.take(4),
        )
        // all the way down: the pair ends Home
        var o = order
        while (true) o = movedHomeSectionOrder(o, HomeSection.YOUR_RECORD, up = false) ?: break
        assertEquals(listOf(HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES), o.takeLast(2))
        assertEquals(HomeSection.entries.size, o.size)
        // already last: nothing moves
        assertNull(movedHomeSectionOrder(o, HomeSection.YOUR_RECORD, up = false))
    }

    @Test
    fun `a neighbour moves past the card and its mixes in one tap`() {
        val order = effectiveHomeSectionOrder(emptyList(), promoted = true)
        // Your playlists up: over the pair, not between them
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_PLAYLISTS, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES),
            movedHomeSectionOrder(order, HomeSection.YOUR_PLAYLISTS, up = true)!!.take(4),
        )
        // Discover down: under the pair
        assertEquals(
            listOf(HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.DISCOVER, HomeSection.YOUR_PLAYLISTS),
            movedHomeSectionOrder(order, HomeSection.DISCOVER, up = false)!!.take(4),
        )
    }

    @Test
    fun `the mixes have no place of their own, so moving them does nothing`() {
        val order = effectiveHomeSectionOrder(emptyList(), promoted = true)
        assertNull(movedHomeSectionOrder(order, HomeSection.RECORD_MIXES, up = true))
        assertNull(movedHomeSectionOrder(order, HomeSection.RECORD_MIXES, up = false))
    }

    @Test
    fun `hiding the card keeps the mixes, in the card's place`() {
        val order = effectiveHomeSectionOrder(listOf("discover", "radios", "your_record", "top_albums"), promoted = true)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.RADIOS, HomeSection.RECORD_MIXES, HomeSection.TOP_ALBUMS),
            visibleHomeSections(order, setOf(HomeSection.YOUR_RECORD), communityOn = false).take(4),
        )
        // the mixes hidden: the card alone; both hidden: neither, and both keep their place in the order
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.RADIOS, HomeSection.YOUR_RECORD, HomeSection.TOP_ALBUMS),
            visibleHomeSections(order, setOf(HomeSection.RECORD_MIXES), communityOn = false).take(4),
        )
        val neither = visibleHomeSections(order, setOf(HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES), communityOn = false)
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.RADIOS, HomeSection.TOP_ALBUMS), neither.take(3))
        assertTrue(HomeSection.RECORD_MIXES in order)
    }

    @Test
    fun `withRecordMixesUnderRecord puts the mixes under the card, and leaves an order without the card alone`() {
        assertEquals(
            listOf(HomeSection.RADIOS, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.DISCOVER),
            withRecordMixesUnderRecord(listOf(HomeSection.RECORD_MIXES, HomeSection.RADIOS, HomeSection.YOUR_RECORD, HomeSection.DISCOVER)),
        )
        // without the mixes in it: they're added under the card
        assertEquals(
            listOf(HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.RADIOS),
            withRecordMixesUnderRecord(listOf(HomeSection.YOUR_RECORD, HomeSection.RADIOS)),
        )
        val noCard = listOf(HomeSection.RECORD_MIXES, HomeSection.RADIOS)
        assertEquals(noCard, withRecordMixesUnderRecord(noCard))
    }
}
