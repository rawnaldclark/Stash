package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

/** Covers learnt after the queue was set go on the queued tracks that have none, never over a cover a track has. */
class QueueArtTest {
    private fun t(id: Long, art: String? = null, path: String? = null) = Track(id = id, title = "T$id", artist = "A", albumArtUrl = art, albumArtPath = path, dateAdded = 0)

    @Test fun `a bare track takes its cover, the others stay as they were`() {
        val queue = listOf(t(1), t(2, art = "https://own"), t(3, path = "/art/3.jpg"), t(4), t(1))
        val filled = QueueArt.fill(queue, mapOf(1L to "https://one", 2L to "https://other", 3L to "https://three", 9L to "https://none"))
        assertThat(filled.map { it.albumArtUrl }).containsExactly("https://one", "https://own", null, null, "https://one").inOrder()
        assertThat(QueueArt.fill(queue, emptyMap())).isSameInstanceAs(queue)
    }

    @Test fun `neighbours go as one replace`() {
        assertThat(QueueArt.runs(listOf(12, 2, 3, 9, 4, 11))).containsExactly(2..4, 9..9, 11..12).inOrder()
        assertThat(QueueArt.runs(listOf(0))).containsExactly(0..0)
        assertThat(QueueArt.runs(emptyList())).isEmpty()
        assertThat(QueueArt.runs((0 until 50).toList())).containsExactly(0..49)
    }
}
