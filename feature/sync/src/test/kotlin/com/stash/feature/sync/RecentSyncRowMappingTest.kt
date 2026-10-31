package com.stash.feature.sync

import com.stash.core.model.SyncDisplayStatus
import com.stash.feature.sync.components.SyncRowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentSyncRowMappingTest {

    @Test
    fun `stream-only sync shows surfaced count and Stream only label`() {
        val row = SyncHistoryInfo(
            id = 1, startedAt = 0, completedAt = 1000, status = "COMPLETED",
            tracksDownloaded = 0, tracksFailed = 0, newTracksFound = 1578,
            playlistsChecked = 50, streamingMode = true,
            displayStatus = SyncDisplayStatus.Success,
        ).toRecentSyncRow(relativeTime = "35m ago")
        assertEquals("Stream only", row.modeLabel)
        assertEquals(1578, row.added)
        assertEquals("surfaced", row.addedNoun)
    }

    @Test
    fun `download sync shows downloaded count and Download label`() {
        val row = SyncHistoryInfo(
            id = 2, startedAt = 0, completedAt = 1000, status = "COMPLETED",
            tracksDownloaded = 340, tracksFailed = 0, newTracksFound = 400,
            playlistsChecked = 12, streamingMode = false,
            displayStatus = SyncDisplayStatus.Success,
        ).toRecentSyncRow(relativeTime = "1h ago")
        assertEquals("Download", row.modeLabel)
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

    // ── A playlist the sync couldn't read: the pill and the sentence (#747) ──

    private fun completedRun(diagnostics: String?) = SyncHistoryInfo(
        id = 5, startedAt = 0, completedAt = 1000, status = "COMPLETED",
        tracksDownloaded = 0, tracksFailed = 0, newTracksFound = 3, playlistsChecked = 15,
        streamingMode = true, diagnostics = diagnostics,
        displayStatus = SyncDisplayStatus.Success,
    ).toRecentSyncRow(relativeTime = "5m ago")

    @Test
    fun `a run that couldn't read a playlist names it for the pill and the sentence`() {
        // As PlaylistFetchWorker records it.
        val row = completedRun(
            """[{"service":"SPOTIFY","step":"getDailyMixes","status":"SUCCESS","itemCount":2},""" +
                """{"service":"SPOTIFY","step":"getUserPlaylists","status":"ERROR","itemCount":14,""" +
                """"errorMessage":"couldn't read 1 playlist: My Playlist #11 (kept as it was)",""" +
                """"unreadPlaylists":["My Playlist #11"]}]""",
        )
        assertEquals(listOf("My Playlist #11"), row.unreadPlaylists)
        assertEquals("still a healthy receipt: nothing was lost", SyncRowStatus.HEALTHY, row.status)
    }

    @Test
    fun `a healthy run shows no pill and no sentence`() {
        val row = completedRun(
            """[{"service":"SPOTIFY","step":"getLikedSongs","status":"SUCCESS","itemCount":402},""" +
                """{"service":"SPOTIFY","step":"getUserPlaylists","status":"SUCCESS","itemCount":15}]""",
        )
        assertEquals(emptyList<String>(), row.unreadPlaylists)
    }

    @Test
    fun `steps that don't decode show no pill, and the row keeps them as they were`() {
        val row = completedRun("not json")
        assertEquals(emptyList<String>(), row.unreadPlaylists)
        assertEquals("not json", row.diagnostics)
    }
}
