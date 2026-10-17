package com.stash.feature.library.allsongs

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.ScannedSongEntity
import com.stash.feature.library.SortOrder
import org.junit.Test

class SortAndFilterTest {
    private fun row(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "",
        duration: Long = 1_000,
        modified: Long = 0,
        added: Long = 0,
    ) = ScannedSongEntity(
        id = id, folderId = 1, uri = "content://x/$id", title = title, artist = artist, album = album,
        durationMs = duration, fileFormat = "mp3", fileSizeBytes = 1, lastModified = modified, dateAdded = added,
    )

    private fun ids(rows: List<ScannedSongEntity>) = rows.map { it.id }

    @Test
    fun `A to Z ignores case`() {
        val rows = listOf(row(1, "banana"), row(2, "Apple"), row(3, "cherry"))
        assertThat(ids(sortAndFilter(rows, SortOrder.ALPHABETICAL, ""))).containsExactly(2L, 1L, 3L).inOrder()
    }

    @Test
    fun `search matches title, artist or album, ignoring case and outer spaces`() {
        val rows = listOf(
            row(1, "Hello", artist = "Adele"),
            row(2, "Other", artist = "Someone", album = "HELLO again"),
            row(3, "Nope", artist = "Nobody"),
        )
        assertThat(ids(sortAndFilter(rows, SortOrder.ALPHABETICAL, "  hello "))).containsExactly(1L, 2L)
        assertThat(ids(sortAndFilter(rows, SortOrder.ALPHABETICAL, "adele"))).containsExactly(1L)
    }

    @Test
    fun `recent uses the modified time, and the day first found when there is none`() {
        val rows = listOf(
            row(1, "a", modified = 100),
            row(2, "b", modified = 300),
            row(3, "c", modified = 0, added = 200),
        )
        assertThat(ids(sortAndFilter(rows, SortOrder.RECENT, ""))).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `duration is longest first`() {
        val rows = listOf(row(1, "a", duration = 10), row(2, "b", duration = 30), row(3, "c", duration = 20))
        assertThat(ids(sortAndFilter(rows, SortOrder.DURATION, ""))).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `most played is not offered here and leaves the order alone`() {
        val rows = listOf(row(2, "b"), row(1, "a"))
        assertThat(ids(sortAndFilter(rows, SortOrder.MOST_PLAYED, ""))).containsExactly(2L, 1L).inOrder()
    }
}