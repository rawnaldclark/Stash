package com.stash.core.data.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.listen.SessionRecap
import org.junit.Test

/** Which recaps are kept: a week, 20 at most, newest first. */
class RecapShelfTest {
    private val day = 24L * 60 * 60 * 1000
    private fun recap(id: String, endedAt: Long) = SessionRecap(id = id, stationId = "s", startedAt = endedAt - 1, endedAt = endedAt)

    @Test fun `a new recap goes first, and one a week old is dropped`() {
        val now = 30 * day
        val kept = RecapShelf.add(listOf(recap("old", now - 7 * day), recap("recent", now - 6 * day)), recap("new", now), now)
        assertThat(kept.map { it.id }).containsExactly("new", "recent").inOrder()
    }

    @Test fun `the same stay again replaces its recap`() {
        val now = 30 * day
        val kept = RecapShelf.add(listOf(recap("a", now - 1000)), recap("a", now), now)
        assertThat(kept).containsExactly(recap("a", now))
    }

    @Test fun `only the newest 20 are kept`() {
        val now = 30 * day
        val many = (1..25).map { recap("r$it", now - it * 1000L) }
        val kept = RecapShelf.add(many, recap("new", now), now)
        assertThat(kept).hasSize(20)
        assertThat(kept.first().id).isEqualTo("new")
        assertThat(kept.last().id).isEqualTo("r19")
    }
}
