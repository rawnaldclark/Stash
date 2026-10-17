package com.stash.core.data.repository

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.scannedTrackId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An All Songs file has no `tracks` row and must never get one. [MusicRepositoryImpl.ensureTrackPersisted]
 * is the funnel every like, download, block, flag and playlist save from Now Playing goes through; before
 * it refused, a scanned "Song" by "Artist" adopted the library's "Song" by its words, so "Delete & block"
 * deleted that library song and its file.
 */
class MusicRepositoryScannedSongTest {

    private val trackDao = mockk<TrackDao>(relaxed = true)

    private val scanned = Track(
        id = scannedTrackId(3L),
        title = "Song",
        artist = "Artist",
        filePath = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSong.mp3",
        source = MusicSource.LOCAL,
        isDownloaded = true,
    )

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
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )

    @Test fun `ensureTrackPersisted refuses a scanned song and never adopts a same-named library song`() = runTest {
        // A library song with the scanned file's exact words: the old path returned its id.
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns
            listOf(TrackEntity(id = 8L, title = "Song", artist = "Artist", source = MusicSource.SPOTIFY))

        try {
            repo().ensureTrackPersisted(scanned)
            fail("a scanned song must not be persisted")
        } catch (expected: IllegalStateException) {
            // The callers' "Couldn't ..." path.
        }
        coVerify(exactly = 0) { trackDao.insert(any()) }
        coVerify(exactly = 0) { trackDao.findAllByCanonicalIdentity(any(), any()) }
    }

    @Test fun `deleteTrack never deletes a scanned song or its file`() = runTest {
        val repo = repo()

        assertFalse(repo.deleteTrack(scanned))
        coVerify(exactly = 0) { trackDao.delete(any()) }
    }

    @Test fun `a library song is still persisted as before`() = runTest {
        coEvery { trackDao.getById(5L) } returns TrackEntity(id = 5L, title = "Song", artist = "Artist", source = MusicSource.SPOTIFY)

        assertTrue(repo().ensureTrackPersisted(Track(id = 5L, title = "Song", artist = "Artist")) == 5L)
    }
}
