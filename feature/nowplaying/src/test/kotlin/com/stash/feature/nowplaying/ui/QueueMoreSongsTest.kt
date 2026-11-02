package com.stash.feature.nowplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** A big queue's window in Up next: the last row says how many songs follow it. */
class QueueMoreSongsTest {

    @Test fun `the row counts the songs after the window`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("And 72,093 more songs", moreSongsLabel(72_093))
            assertEquals("And 1 more song", moreSongsLabel(1))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun `Next stays on at the window's last song while more follow`() {
        // 500 in the window, the last one playing, 72,000 after it.
        assertTrue(hasNextInQueue(queueSize = 0 + 500 + 72_000, currentIndex = 0 + 499, repeatAll = false))
        assertFalse(hasNextInQueue(queueSize = 0 + 500 + 0, currentIndex = 0 + 499, repeatAll = false))
        // Previous at the window's first song, with songs before it.
        assertTrue(hasPreviousInQueue(queueSize = 1_000 + 500 + 0, currentIndex = 1_000 + 0, repeatAll = false))
    }
}
