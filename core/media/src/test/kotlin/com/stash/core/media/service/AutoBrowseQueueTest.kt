package com.stash.core.media.service

import com.stash.core.data.db.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoBrowseQueueTest {

    private fun track(
        id: Long,
        downloaded: Boolean = true,
        streamable: Boolean = false,
        checkedAt: Long? = null,
    ) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        filePath = if (downloaded) "/music/$id.flac" else null,
        isDownloaded = downloaded,
        isStreamable = streamable,
        isStreamableCheckedAt = checkedAt,
    )

    // ── mediaId round-trip ───────────────────────────────────────────

    @Test
    fun `childMediaId round-trips through parse for a playlist parent`() {
        val id = AutoBrowseQueue.childMediaId("PLAYLIST_42", trackId = 7L)
        val parsed = AutoBrowseQueue.parse(id)
        assertEquals("PLAYLIST_42", parsed?.parentId)
        assertEquals(7L, parsed?.trackId)
    }

    @Test
    fun `childMediaId round-trips for the recently-added parent (underscore in parent id)`() {
        val id = AutoBrowseQueue.childMediaId("RECENTLY_ADDED", trackId = 99L)
        val parsed = AutoBrowseQueue.parse(id)
        assertEquals("RECENTLY_ADDED", parsed?.parentId)
        assertEquals(99L, parsed?.trackId)
    }

    @Test
    fun `parse rejects bare track ids, shuffle ids, and garbage`() {
        assertNull(AutoBrowseQueue.parse("123"))
        assertNull(AutoBrowseQueue.parse("SHUFFLE_PLAY_42"))
        assertNull(AutoBrowseQueue.parse("PLAYLIST_42"))
        assertNull(AutoBrowseQueue.parse(""))
        // Right prefix but no numeric track id at the end.
        assertNull(AutoBrowseQueue.parse(AutoBrowseQueue.PREFIX + "PLAYLIST_42_abc"))
    }

    // ── queuePlan ────────────────────────────────────────────────────

    @Test
    fun `queuePlan starts at the tapped track with the full playlist queued in order`() {
        val tracks = listOf(track(1), track(2), track(3), track(4))
        val plan = AutoBrowseQueue.queuePlan(tracks, tappedTrackId = 3L)
        assertEquals(listOf(1L, 2L, 3L, 4L), plan.tracks.map { it.id })
        assertEquals(2, plan.startIndex)
    }

    @Test
    fun `queuePlan keeps streamable-only tracks and drops unplayable ones`() {
        val tracks = listOf(
            track(1),
            track(2, downloaded = false, streamable = false, checkedAt = 1L), // confirmed unplayable — dropped
            track(3, downloaded = false, streamable = true),
            track(4),
        )
        val plan = AutoBrowseQueue.queuePlan(tracks, tappedTrackId = 4L)
        assertEquals(listOf(1L, 3L, 4L), plan.tracks.map { it.id })
        // Index is within the FILTERED list — same filter the browse UI used.
        assertEquals(2, plan.startIndex)
    }

    /**
     * A synced row nobody has probed yet (is_streamable=0, checked_at=null) is
     * LISTED by onGetChildren (isPlayableInAuto). The tap rebuilds the queue from
     * the same rows, so it must keep that row too — the bare is_streamable
     * filter dropped it, shifted the start index, and played the wrong song.
     */
    @Test
    fun `queuePlan keeps never-checked synced rows - the same rows the car listed`() {
        val tracks = listOf(track(1), track(2, downloaded = false, streamable = false), track(3))
        val plan = AutoBrowseQueue.queuePlan(tracks, tappedTrackId = 2L)
        assertEquals(listOf(1L, 2L, 3L), plan.tracks.map { it.id })
        assertEquals(1, plan.startIndex)
    }

    @Test
    fun `queuePlan falls back to index 0 when the tapped track is missing`() {
        val tracks = listOf(track(1), track(2))
        val plan = AutoBrowseQueue.queuePlan(tracks, tappedTrackId = 999L)
        assertEquals(0, plan.startIndex)
    }

    @Test
    fun `queuePlan of an empty playlist is empty with index 0`() {
        val plan = AutoBrowseQueue.queuePlan(emptyList(), tappedTrackId = 1L)
        assertEquals(0, plan.tracks.size)
        assertEquals(0, plan.startIndex)
    }
}
