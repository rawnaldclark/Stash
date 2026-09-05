package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

/**
 * Issue #468: with shuffle on, the queue sheet kept listing the unshuffled
 * order while playback followed Media3's shuffle order — "Up next" lied.
 * [QueueDisplay] gets the shuffle walk (timeline indices in play order) and
 * must display in that order, keep the mapping back to timeline slots, and
 * ignore the logical queue while shuffle is on.
 */
class QueueDisplayShuffleTest {
    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A$id")
    private val t1 = track(1)
    private val t2 = track(2)
    private val t3 = track(3)
    private val t4 = track(4)

    @Test
    fun `shuffle walk displays the timeline in play order with the mapping kept`() {
        val result = QueueDisplay.compute(
            timelineQueue = listOf(t1, t2, t3, t4),
            timelineIndex = 2,                       // t3 is playing
            logicalQueue = listOf(t1, t2, t3, t4),
            currentTrackId = 3L,
            shuffledTimelineIndices = listOf(2, 0, 3, 1), // t3, t1, t4, t2
        )
        assertThat(result.queue.map { it.id }).containsExactly(3L, 1L, 4L, 2L).inOrder()
        assertThat(result.currentIndex).isEqualTo(0)
        assertThat(result.isLogical).isFalse()
        assertThat(result.timelineIndices).containsExactly(2, 0, 3, 1).inOrder()
    }

    @Test
    fun `a current track deep in the shuffle walk gets the right index`() {
        val result = QueueDisplay.compute(
            timelineQueue = listOf(t1, t2, t3, t4),
            timelineIndex = 1,                       // t2 is playing, last in the walk
            logicalQueue = listOf(t1, t2, t3, t4),
            currentTrackId = 2L,
            shuffledTimelineIndices = listOf(2, 0, 3, 1),
        )
        assertThat(result.currentIndex).isEqualTo(3)
    }

    @Test
    fun `without a shuffle walk the logical display is unchanged`() {
        val result = QueueDisplay.compute(
            timelineQueue = listOf(t1, t3),
            timelineIndex = 0,
            logicalQueue = listOf(t1, t2, t3),
            currentTrackId = 1L,
            shuffledTimelineIndices = null,
        )
        assertThat(result.queue.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(result.isLogical).isTrue()
        assertThat(result.timelineIndices).isNull()
    }

    @Test
    fun `walking the shuffle order stops on a cycle and caps at the item count`() {
        // A well-formed walk: 2 → 0 → 3 → 1 → end.
        val next = mapOf(2 to 0, 0 to 3, 3 to 1, 1 to -1)
        assertThat(QueueDisplay.walkShuffleOrder(count = 4, first = 2) { next.getValue(it) })
            .containsExactly(2, 0, 3, 1).inOrder()
        // A degenerate one (a mock that always answers 0) must not spin forever.
        assertThat(QueueDisplay.walkShuffleOrder(count = 4, first = 0) { 0 }).containsExactly(0)
        // Nothing to walk.
        assertThat(QueueDisplay.walkShuffleOrder(count = 0, first = -1) { -1 }).isEmpty()
    }
}
