package com.stash.feature.sync

import com.stash.core.data.db.dao.UnmatchedTrackView
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Failed Matches list's keys, in its order: how the screen finds a song's row by position
 * when it is off screen, as when Now Playing's "Find a better match" opens the screen on a flagged
 * song below every unmatched one.
 */
class FailedMatchesListKeysTest {

    private fun unmatched(trackId: Long) = UnmatchedTrackView(
        id = trackId + 1000, trackId = trackId, title = "Song $trackId", artist = "Artist",
        albumArtUrl = null, createdAt = 0L, rejectedVideoId = null, searchQuery = "Artist - Song $trackId",
    )

    private fun flagged(trackId: Long) = FlaggedTrackRow(
        trackId = trackId, title = "Flagged $trackId", artist = "Artist", albumArtUrl = null,
        currentYoutubeId = "wrong$trackId", currentFilePath = null, searchQuery = "Artist - Flagged $trackId",
    )

    @Test fun `the keys follow the list, header then banner then unmatched songs then the flagged section`() {
        val keys = failedMatchesListKeys(
            tracks = listOf(unmatched(1L), unmatched(2L)),
            flagged = listOf(flagged(7L)),
            approvedBanner = true,
        )
        assertEquals(listOf("header", "approved-downloads", 1001L, 1002L, "flagged_header", "flagged_7"), keys)
    }

    @Test fun `no banner and no flagged songs, no keys for them`() {
        val keys = failedMatchesListKeys(listOf(unmatched(1L)), flagged = emptyList(), approvedBanner = false)
        assertEquals(listOf("header", 1001L), keys)
    }

    @Test fun `a flagged song under hundreds of unmatched ones is found by its place`() {
        val tracks = (1L..300L).map(::unmatched)
        val keys = failedMatchesListKeys(tracks, listOf(flagged(7L), flagged(8L)), approvedBanner = false)
        // Header, 300 songs, the flagged section's header, then the first flagged song.
        assertEquals(302, keys.indexOf(flaggedRowKey(7L)))
        assertEquals(303, keys.indexOf(flaggedRowKey(8L)))
    }

    @Test fun `the row keys the screen reveals by are the list's own`() {
        val song = unmatched(5L)
        val keys = failedMatchesListKeys(listOf(song), listOf(flagged(9L)), approvedBanner = false)
        assertEquals(1, keys.indexOf(unmatchedRowKey(song)))
        assertEquals(3, keys.indexOf(flaggedRowKey(9L)))
        // A song in neither list has no place.
        assertEquals(-1, keys.indexOf(flaggedRowKey(42L)))
    }
}
