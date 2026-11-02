package com.stash.feature.settings

import com.stash.core.data.prefs.HomeSection
import com.stash.core.data.prefs.effectiveHomeSectionOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Appearance › Home layout's entries ([homeLayoutEntries]): Your record's mixes ride under the card. */
class HomeLayoutEntriesTest {

    @Test fun `the mixes are no entry of their own, they hang under your record`() {
        val entries = homeLayoutEntries(effectiveHomeSectionOrder(emptyList(), promoted = true))
        assertFalse(entries.any { it.section == HomeSection.RECORD_MIXES })
        assertEquals(HomeSection.entries.size - 1, entries.size)
        val record = entries.single { it.section == HomeSection.YOUR_RECORD }
        assertEquals(HomeSection.RECORD_MIXES, record.under)
        // nothing else has anything under it
        assertTrue(entries.filter { it.section != HomeSection.YOUR_RECORD }.all { it.under == null })
    }

    @Test fun `the movers count only the sections with a place of their own`() {
        // Your record last but for its own mixes: down would do nothing, so it isn't offered
        val order = (HomeSection.entries - HomeSection.YOUR_RECORD - HomeSection.RECORD_MIXES) +
            HomeSection.YOUR_RECORD + HomeSection.RECORD_MIXES
        val entries = homeLayoutEntries(order)
        val record = entries.last()
        assertEquals(HomeSection.YOUR_RECORD, record.section)
        assertTrue(record.canMoveUp)
        assertFalse(record.canMoveDown)
        // the first can't go up, every other entry can go down
        assertFalse(entries.first().canMoveUp)
        assertTrue(entries.dropLast(1).all { it.canMoveDown })
    }

    @Test fun `your record directly above its mixes can still move down, past the next section`() {
        val entries = homeLayoutEntries(effectiveHomeSectionOrder(emptyList(), promoted = true))
        // Discover, Your record (+ its mixes), Your playlists, …
        assertEquals(HomeSection.YOUR_RECORD, entries[1].section)
        assertTrue(entries[1].canMoveDown)
        assertEquals(HomeSection.YOUR_PLAYLISTS, entries[2].section)
        assertTrue(entries[2].canMoveUp)
    }

    @Test fun `an order without the mixes shows none under the card`() {
        val entries = homeLayoutEntries(HomeSection.entries - HomeSection.RECORD_MIXES)
        assertNull(entries.single { it.section == HomeSection.YOUR_RECORD }.under)
    }
}
