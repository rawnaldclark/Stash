package com.stash.feature.sync

import com.stash.core.model.SyncDisplayStatus
import com.stash.feature.sync.components.SyncRowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentSyncRowMappingTest {

    @Test
    fun `online sync shows surfaced count and Online label`() {
        val row = SyncHistoryInfo(
            id = 1, startedAt = 0, completedAt = 1000, status = "COMPLETED",
            tracksDownloaded = 0, tracksFailed = 0, newTracksFound = 1578,
            playlistsChecked = 50, streamingMode = true,
            displayStatus = SyncDisplayStatus.Success,
        ).toRecentSyncRow(relativeTime = "35m ago")
        assertEquals("Online", row.modeLabel)
        assertEquals(1578, row.added)
        assertEquals("surfaced", row.addedNoun)
    }

    @Test
    fun `offline sync shows downloaded count and Offline label`() {
        val row = SyncHistoryInfo(
            id = 2, startedAt = 0, completedAt = 1000, status = "COMPLETED",
            tracksDownloaded = 340, tracksFailed = 0, newTracksFound = 400,
            playlistsChecked = 12, streamingMode = false,
            displayStatus = SyncDisplayStatus.Success,
        ).toRecentSyncRow(relativeTime = "1h ago")
        assertEquals("Offline", row.modeLabel)
        assertEquals(340, row.added)
        assertEquals("downloaded", row.addedNoun)
    }

    @Test
    fun `pre-migration row suppresses mode label and falls back to downloaded`() {
        val row = SyncHistoryInfo(
            id = 3, startedAt = 0, completedAt = 1000, status = "COMPLETED",
            tracksDownloaded = 5, tracksFailed = 0, newTracksFound = 99, streamingMode = null,
            displayStatus = SyncDisplayStatus.Success,
        ).toRecentSyncRow(relativeTime = "yesterday")
        assertNull(row.modeLabel)
        // newTracksFound (99) differs from tracksDownloaded (5) so this proves the
        // null-mode fallback selects tracksDownloaded, not the surfaced count.
        assertEquals(5, row.added)
    }

    @Test
    fun `cancelled sync is marked CANCELLED not green`() {
        val row = SyncHistoryInfo(
            id = 4, startedAt = 0, completedAt = 1000, status = "CANCELLED",
            tracksDownloaded = 0, tracksFailed = 0, streamingMode = true,
            displayStatus = SyncDisplayStatus.Cancelled,
        ).toRecentSyncRow(relativeTime = "2m ago")
        assertEquals(SyncRowStatus.CANCELLED, row.status)
    }
}
