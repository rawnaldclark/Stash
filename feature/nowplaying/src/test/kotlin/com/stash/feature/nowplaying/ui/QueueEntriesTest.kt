package com.stash.feature.nowplaying.ui

import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** Queue rows are keyed by song, not position, so moves and removals don't reshuffle row identity. */
class QueueEntriesTest {

    private fun t(id: Long) = Track(id = id, title = "t$id", artist = "a")
    private fun keys(vararg ids: Long) = queueEntries(ids.map(::t)).map { it.uid }

    @Test
    fun `a song keeps its key when the queue around it changes`() {
        assertEquals(listOf("1:1", "2:1", "3:1"), keys(1, 2, 3))
        assertEquals(listOf("3:1", "1:1", "2:1"), keys(3, 1, 2)) // moved
        assertEquals(listOf("1:1", "3:1"), keys(1, 3))           // 2 removed
    }

    @Test
    fun `copies of the same song get distinct keys`() {
        assertEquals(listOf("5:1", "6:1", "5:2"), keys(5, 6, 5))
    }
}
