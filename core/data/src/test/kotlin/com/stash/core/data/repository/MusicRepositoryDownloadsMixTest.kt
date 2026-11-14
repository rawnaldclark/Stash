package com.stash.core.data.repository

import android.content.Context
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.OrphanSweep
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRepositoryDownloadsMixTest {

    @Test
    fun `ensureDownloadsMixSeeded inserts a new playlist when none exists`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val inserted = slot<PlaylistEntity>()
        coEvery { playlistDao.ensurePlaylist(capture(inserted)) } returns 42L

        val repo = buildRepo(playlistDao = playlistDao)
        val id = repo.ensureDownloadsMixSeeded()

        assertEquals(42L, id)
        assertEquals(PlaylistType.DOWNLOADS_MIX, inserted.captured.type)
        assertEquals(MusicSource.BOTH, inserted.captured.source)
        assertEquals("stash_downloads_mix", inserted.captured.sourceId)
        assertEquals("Your Downloads", inserted.captured.name)
        assertEquals(false, inserted.captured.syncEnabled)
    }

    @Test
    fun `ensureDownloadsMixSeeded is idempotent when playlist already exists`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        coEvery { playlistDao.ensurePlaylist(any()) } returns 7L

        val repo = buildRepo(playlistDao = playlistDao)
        val id = repo.ensureDownloadsMixSeeded()

        assertEquals(7L, id)
        coVerify(exactly = 1) { playlistDao.ensurePlaylist(any()) }
    }

    @Test
    fun `linkTrackToDownloadsMix delegates to strict active-link transaction`() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        coEvery { playlistDao.ensurePlaylistAndActiveCrossRef(any(), 99L) } returns 42L

        // The recount is a COUNT (trackDao.countByPlaylist), which the relaxed mock answers 0.
        val trackDao = mockk<TrackDao>(relaxed = true)

        val repo = buildRepo(playlistDao = playlistDao, trackDao = trackDao)
        repo.linkTrackToDownloadsMix(trackId = 99L)

        coVerify { playlistDao.ensurePlaylistAndActiveCrossRef(any(), 99L) }
    }

    private fun buildRepo(
        playlistDao: PlaylistDao = mockk(relaxed = true),
        context: Context = mockk(relaxed = true),
        trackDao: TrackDao = mockk(relaxed = true),
        syncHistoryDao: SyncHistoryDao = mockk(relaxed = true),
        downloadQueueDao: DownloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true),
        // Default: a relaxed mock returns false for the anyAccumulate() Boolean
        // suspend fun, so the gate is open for pre-existing tests with no stub.
        syncPreferencesManager: SyncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer: com.stash.core.data.sync.SingleTrackDownloadEnqueuer = mockk(relaxed = true),
        downloadJobs: DownloadJobRegistry = DownloadJobRegistry(),
    ): MusicRepositoryImpl = MusicRepositoryImpl(
        context = context,
        trackDao = trackDao,
        playlistDao = playlistDao,
        syncHistoryDao = syncHistoryDao,
        downloadQueueDao = downloadQueueDao,
        discoveryQueueDao = discoveryQueueDao,
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        libraryScopePreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = syncPreferencesManager,
        singleTrackDownloadEnqueuer = singleTrackDownloadEnqueuer,
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
        downloadJobs = downloadJobs,
    )

    private fun downloadedTrack(id: Long, filePath: String = "/music/$id.flac") = TrackEntity(
        id = id,
        title = "T$id",
        artist = "A$id",
        durationMs = 1000L,
        source = MusicSource.SPOTIFY,
        isDownloaded = true,
        filePath = filePath,
        canonicalTitle = "t$id",
        canonicalArtist = "a$id",
    )

    private fun notDownloadedTrack(id: Long) = TrackEntity(
        id = id,
        title = "T$id",
        artist = "A$id",
        durationMs = 1000L,
        source = MusicSource.SPOTIFY,
        isDownloaded = false,
        filePath = null,
        canonicalTitle = "t$id",
        canonicalArtist = "a$id",
    )

    @Test
    fun `queueDownload requeues an existing non-terminal row instead of refusing`() = runTest {
        // The bug: a stuck non-terminal row (streaming-mode PENDING or deferred
        // WAITING_FOR_LOSSLESS) made queueDownload a silent no-op — "Couldn't
        // queue download" for every already-synced track. It must now reset the
        // row and drive a single-track download instead.
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getById(5L) } returns notDownloadedTrack(5L)
        val dq = mockk<DownloadQueueDao>(relaxed = true)
        val existing = mockk<DownloadQueueEntity>(relaxed = true)
        every { existing.id } returns 88L
        every { existing.status } returns DownloadStatus.WAITING_FOR_LOSSLESS
        coEvery { dq.getByTrackId(5L) } returns existing
        coEvery { dq.requeueForTap(88L) } returns 1
        val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

        val repo = buildRepo(trackDao = trackDao, downloadQueueDao = dq, singleTrackDownloadEnqueuer = enqueuer)
        val result = repo.queueDownload(5L)

        assertEquals(true, result)
        coVerify { dq.requeueForTap(88L) }
        coVerify { enqueuer.enqueue(88L) }
        coVerify(exactly = 0) { dq.insert(any()) }
    }

    @Test
    fun `queueDownload leaves a song that is downloading right now alone`() = runTest {
        // The bug: a tap reset the row to PENDING under the live download, so the
        // tap's own worker cancelled it and started over.
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getById(5L) } returns notDownloadedTrack(5L)
        val dq = mockk<DownloadQueueDao>(relaxed = true)
        val existing = mockk<DownloadQueueEntity>(relaxed = true)
        every { existing.id } returns 88L
        coEvery { dq.getByTrackId(5L) } returns existing
        coEvery { dq.getStatusById(88L) } returns DownloadStatus.IN_PROGRESS
        val jobs = DownloadJobRegistry()
        val download = Job()
        assertTrue(jobs.claimAndRegister(88L, download) { true })
        val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

        val repo = buildRepo(trackDao = trackDao, downloadQueueDao = dq, singleTrackDownloadEnqueuer = enqueuer, downloadJobs = jobs)

        assertEquals(true, repo.queueDownload(5L))
        coVerify { dq.markUserRequested(88L) }
        coVerify(exactly = 0) { dq.requeueForTap(any()) }
        coVerify(exactly = 0) { dq.insert(any()) }
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
        assertTrue(download.isActive)
        download.cancel()
    }

    @Test
    fun `queueDownload resets a row left in progress by a process that died`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getById(5L) } returns notDownloadedTrack(5L)
        val dq = mockk<DownloadQueueDao>(relaxed = true)
        val existing = mockk<DownloadQueueEntity>(relaxed = true)
        every { existing.id } returns 88L
        coEvery { dq.getByTrackId(5L) } returns existing
        coEvery { dq.getStatusById(88L) } returns DownloadStatus.IN_PROGRESS
        coEvery { dq.requeueForTap(88L) } returns 1
        val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

        // No live job holds row 88: IN_PROGRESS is a leftover, not a download.
        val repo = buildRepo(trackDao = trackDao, downloadQueueDao = dq, singleTrackDownloadEnqueuer = enqueuer)

        assertEquals(true, repo.queueDownload(5L))
        coVerify { dq.requeueForTap(88L) }
        coVerify { enqueuer.enqueue(88L) }
        coVerify(exactly = 0) { dq.markUserRequested(any()) }
    }

    @Test
    fun `queueDownload inserts a new row when none exists then enqueues it`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getById(5L) } returns notDownloadedTrack(5L)
        val dq = mockk<DownloadQueueDao>(relaxed = true)
        coEvery { dq.getByTrackId(5L) } returns null
        coEvery { dq.insert(any()) } returns 77L
        val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

        val repo = buildRepo(trackDao = trackDao, downloadQueueDao = dq, singleTrackDownloadEnqueuer = enqueuer)
        val result = repo.queueDownload(5L)

        assertEquals(true, result)
        coVerify { enqueuer.enqueue(77L) }
    }

    @Test
    fun `queueDownload returns false when the track is already downloaded`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.getById(5L) } returns downloadedTrack(5L)
        val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

        val repo = buildRepo(trackDao = trackDao, singleTrackDownloadEnqueuer = enqueuer)

        assertEquals(false, repo.queueDownload(5L))
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test
    fun `cleanOrphanedMixTracks deletes nothing when any source accumulates`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val prefs = mockk<SyncPreferencesManager>(relaxed = true)
        coEvery { prefs.anyAccumulate() } returns true
        val repo = buildRepo(trackDao = trackDao, syncPreferencesManager = prefs)

        val cleaned = repo.cleanOrphanedMixTracks()

        assertEquals(0, cleaned)
        // Gate short-circuits BEFORE the atomic delete runs.
        coVerify(exactly = 0) { trackDao.deleteOrphanedDownloadedTracks() }
    }

    @Test
    fun `cleanOrphanedMixTracks deletes orphans when all sources refresh`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val prefs = mockk<SyncPreferencesManager>(relaxed = true)
        coEvery { prefs.anyAccumulate() } returns false
        // The atomic DAO method returns the rows it deleted; the repo then
        // removes their files and emits deletions.
        coEvery { trackDao.deleteOrphanedDownloadedTracks() } returns
            OrphanSweep(deleted = listOf(downloadedTrack(1L)), undownloaded = emptyList())
        val repo = buildRepo(trackDao = trackDao, syncPreferencesManager = prefs)

        val cleaned = repo.cleanOrphanedMixTracks()

        assertEquals(1, cleaned)
        coVerify(exactly = 1) { trackDao.deleteOrphanedDownloadedTracks() }
    }
}
