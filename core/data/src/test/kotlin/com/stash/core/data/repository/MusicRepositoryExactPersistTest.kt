package com.stash.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MusicRepositoryExactPersistTest {
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val descriptor = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000, isrc = "GBBPW0100025", spotifyId = "sp1", youtubeId = "yt1")

    // Same argument list as MusicRepositorySharedMixUnshareTest.repo(). If the constructor has changed since, match that test.
    private fun repo() = MusicRepositoryImpl(
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
        sharedMixDao = mockk(relaxed = true),
    )

    private fun row(id: Long) = TrackEntity(id = id, title = "Avril 14th", artist = "Aphex Twin")

    @Test fun `a YouTube id match wins, and the host's ISRC is filled in if the row had none`() = runTest {
        coEvery { trackDao.findByYoutubeId("yt1") } returns row(7)
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(7)
        coVerify { trackDao.backfillIsrcIfMissing(7, "GBBPW0100025") }
        coVerify(exactly = 0) { trackDao.insert(any()) }
    }

    @Test fun `then the Spotify URI, then the exact ISRC`() = runTest {
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findBySpotifyUri("spotify:track:sp1") } returns row(8)
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(8)
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findByIsrc("GBBPW0100025") } returns row(9)
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(9)
    }

    @Test fun `with no exact match it inserts a stream-only row with every id, and never matches by title`() = runTest {
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findByIsrc(any()) } returns null
        val inserted = slot<TrackEntity>()
        coEvery { trackDao.insert(capture(inserted)) } returns 42
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(42)
        with(inserted.captured) {
            assertThat(id).isEqualTo(0)
            assertThat(isrc).isEqualTo("GBBPW0100025")
            assertThat(spotifyUri).isEqualTo("spotify:track:sp1")
            assertThat(youtubeId).isEqualTo("yt1")
            assertThat(isStreamable).isTrue()
            assertThat(canonicalTitle).isEqualTo("avril 14th")
        }
        coVerify(exactly = 0) { trackDao.findByCanonicalIdentity(any(), any()) }
    }
}
