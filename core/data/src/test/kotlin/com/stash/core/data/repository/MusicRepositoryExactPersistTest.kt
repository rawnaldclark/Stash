package com.stash.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.blocklist.BlocklistGuard
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
    private val trackDao = mockk<TrackDao>(relaxed = true).also {
        // No merged duplicates here: a relaxed mock would answer every alias lookup with a row.
        coEvery { it.findByAlias(any(), any()) } returns null
    }
    private val descriptor = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000, isrc = "GBBPW0100025", spotifyId = "sp1", youtubeId = "yt1")

    private val blocklistGuard = mockk<BlocklistGuard>(relaxed = true)

    // Same argument list as MusicRepositorySharedMixUnshareTest.repo(). If the constructor has changed since, match that test.
    private fun repo() = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk(relaxed = true),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = blocklistGuard,
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        libraryScopePreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
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
        coVerify(exactly = 0) { trackDao.findAllByCanonicalIdentity(any(), any()) }
    }

    @Test fun `a new row whose loose key is blocked keeps its strict words`() = runTest {
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findByIsrc(any()) } returns null
        coEvery { blocklistGuard.isKeyBlocked("avril 14th", "aphex twin") } returns true
        val inserted = slot<TrackEntity>()
        coEvery { trackDao.insert(capture(inserted)) } returns 44

        repo().ensureExactTrackPersisted(descriptor.copy(title = "Avril 14th (Live)"))

        assertThat(inserted.captured.canonicalTitle).isEqualTo("avril 14th live")
        assertThat(inserted.captured.canonicalArtist).isEqualTo("aphex twin")
    }

    @Test fun `blank ids are neither looked up nor stored`() = runTest {
        coEvery { trackDao.findByIsrc(any()) } returns null
        val inserted = slot<TrackEntity>()
        coEvery { trackDao.insert(capture(inserted)) } returns 43
        repo().ensureExactTrackPersisted(descriptor.copy(youtubeId = " ", spotifyId = "", isrc = ""))
        coVerify(exactly = 0) { trackDao.findByYoutubeId(any()) }
        coVerify(exactly = 0) { trackDao.findBySpotifyUri(any()) }
        coVerify(exactly = 0) { trackDao.findByIsrc(any()) }
        with(inserted.captured) {
            assertThat(youtubeId).isNull()
            assertThat(spotifyUri).isNull()
            assertThat(isrc).isNull()
        }
    }
}
