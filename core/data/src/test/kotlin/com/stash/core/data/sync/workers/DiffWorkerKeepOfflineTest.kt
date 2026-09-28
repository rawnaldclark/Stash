package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The playlist page's Download switch (#474) at the end of a sync: the worker runs
 * [MusicRepository.queueKeptPlaylists] once, in either mode, and never queues a
 * playlist on its own (one drain per sync). Which playlists that queues is
 * covered in MusicRepositoryQueuePlaylistTest. Runs the WORKER against a real DB,
 * so it proves the sweep is wired, not just that a helper works.
 *
 * Fixture mirrors [DiffWorkerMixNoDownloadTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerKeepOfflineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val remoteSnapshotDao = mockk<com.stash.core.data.db.dao.RemoteSnapshotDao>()
    private val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
    private val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true)
    private val syncStateManager = mockk<SyncStateManager>(relaxed = true)
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val blocklistGuard = mockk<BlocklistGuard>()
    private val streamingPreference = mockk<StreamingPreference>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.ACCUMULATE)
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns emptyList()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `stream-only mode sweeps the kept playlists once after a sync`() = runBlocking {
        assertOneSweep(streamOnly = true)
    }

    @Test
    fun `download mode sweeps the kept playlists once after a sync`() = runBlocking {
        assertOneSweep(streamOnly = false)
    }

    /**
     * Download mode skips a switched-off playlist, but not one kept on the phone:
     * its new songs must be linked for the sweep to queue them. Sync's own
     * download path stays shut (sync_enabled is off), so nothing lands twice.
     */
    @Test
    fun `download mode links a kept playlist whose sync is off, and queues it`() = runBlocking {
        coEvery { streamingPreference.current() } returns false
        val id = insert("Road Trip", "spotify:playlist:mine", PlaylistType.CUSTOM, keepOffline = true)
        snapshotOf3Tracks("spotify:playlist:mine", "Road Trip", PlaylistType.CUSTOM)

        buildWorker().doWork()

        assertEquals(3, db.playlistDao().getTracksForPlaylist(id).size)
        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
        coVerify(exactly = 1) { musicRepository.queueKeptPlaylists(false) }
    }

    /** "Sync now" downloads on any network, and the sweep's songs must too. */
    @Test
    fun `a manual sync's sweep is told it was manual`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        insert("Release Radar", "spotify:playlist:rr", PlaylistType.DAILY_MIX, keepOffline = true)

        buildWorker(manualSync = true).doWork()

        coVerify(exactly = 1) { musicRepository.queueKeptPlaylists(true) }
    }

    private suspend fun assertOneSweep(streamOnly: Boolean) {
        coEvery { streamingPreference.current() } returns streamOnly
        insert("Release Radar", "spotify:playlist:rr", PlaylistType.DAILY_MIX, keepOffline = true)
        insert("Discover Weekly", "spotify:playlist:dw", PlaylistType.DAILY_MIX, keepOffline = true)

        buildWorker().doWork()

        coVerify(exactly = 1) { musicRepository.queueKeptPlaylists(false) }
        coVerify(exactly = 0) { musicRepository.queueDownloadsForPlaylist(any(), any()) }
    }

    private suspend fun insert(
        name: String,
        sourceId: String,
        type: PlaylistType,
        syncEnabled: Boolean = false,
        keepOffline: Boolean = false,
    ): Long = db.playlistDao().insert(
        PlaylistEntity(
            name = name,
            source = MusicSource.SPOTIFY,
            sourceId = sourceId,
            type = type,
            syncEnabled = syncEnabled,
            keepOffline = keepOffline,
        )
    )

    private fun snapshotOf3Tracks(sourceId: String, name: String, type: PlaylistType) {
        val snapshotId = 7L
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(
            RemotePlaylistSnapshotEntity(
                id = snapshotId,
                syncId = 1L,
                source = MusicSource.SPOTIFY,
                sourcePlaylistId = sourceId,
                playlistName = name,
                playlistType = type,
            )
        )
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(snapshotId) } returns (0 until 3).map { i ->
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = snapshotId,
                title = "Track $i",
                artist = "Artist $i",
                spotifyUri = "spotify:track:new$i",
                position = i,
            )
        }
    }

    private fun buildWorker(manualSync: Boolean = false): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to 1L, DiffWorker.KEY_MANUAL_SYNC to manualSync))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = DiffWorker(
                appContext, workerParameters,
                database = db,
                remoteSnapshotDao = remoteSnapshotDao,
                trackDao = db.trackDao(),
                playlistDao = db.playlistDao(),
                downloadQueueDao = downloadQueueDao,
                syncHistoryDao = syncHistoryDao,
                trackMatcher = TrackMatcher(),
                syncStateManager = syncStateManager,
                musicRepository = musicRepository,
                syncPreferencesManager = syncPreferencesManager,
                blocklistGuard = blocklistGuard,
                streamingPreference = streamingPreference,
                syncUndoDao = db.syncUndoDao(),
                syncLog = com.stash.core.data.sync.SyncLog(),
            )
        })
        .build()
}
