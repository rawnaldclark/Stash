package com.stash.core.data.repository

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit (REPLACE→ABORT): with `TrackDao.insert` now ABORTing on a natural-key
 * conflict instead of REPLACE-wiping, [MusicRepositoryImpl.ensureTrackPersisted]
 * must dedup by EVERY unique key before inserting — otherwise a Spotify track
 * whose canonical identity drifted (feat./remaster tags) but whose spotify_uri
 * still matches an existing row would throw at the insert. Previously it
 * checked youtube_id + canonical but not spotify_uri.
 */
class MusicRepositoryEnsureTrackPersistedTest {

    @Test fun `ensureTrackPersisted returns the existing id on a spotify_uri match and does not insert`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        // No id, no youtube match, no canonical match — only the spotify_uri hits.
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findByCanonicalIdentity(any(), any()) } returns null
        coEvery { trackDao.findBySpotifyUri("spotify:track:xyz") } returns
            TrackEntity(
                id = 55L,
                title = "Song (2011 Remaster)",
                artist = "Artist",
                spotifyUri = "spotify:track:xyz",
                canonicalTitle = "song 2011 remaster",
                canonicalArtist = "artist",
                source = MusicSource.SPOTIFY,
            )

        val repo = buildRepo(trackDao)
        val id = repo.ensureTrackPersisted(
            Track(title = "Song", artist = "Artist", spotifyUri = "spotify:track:xyz"),
        )

        assertEquals(55L, id)
        coVerify(exactly = 0) { trackDao.insert(any()) }
    }

    private fun buildRepo(trackDao: TrackDao): MusicRepositoryImpl = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk(relaxed = true),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
    )
}
