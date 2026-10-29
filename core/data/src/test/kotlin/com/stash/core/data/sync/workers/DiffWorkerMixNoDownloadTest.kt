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
 * What a mix does in Offline (Download) mode is decided by its switch, not its
 * type.
 *
 * History: #368 — discovered mixes auto-enabled themselves and DiffWorker's
 * enqueue site tested raw `!streamingMode`, so every track of every rotating mix
 * was queued; users reported 6000+ unwanted downloads. The fix then was a
 * type-based ban: a DAILY_MIX never downloads. The owner's rule (2026-09-16)
 * replaces it: in Download mode a switched-on row downloads, mixes included,
 * and the protection is that discovered mixes start switched OFF.
 *
 * These tests run the WORKER and observe what reaches the DAO. A helper-level
 * test cannot catch this class of bug: it passes whether or not anything calls
 * the helper.
 *
 * Fixture mirrors DiffWorkerTest (Robolectric + in-memory Room + mockk DAOs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerMixNoDownloadTest {

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
        // Offline (Download) mode — the only mode in which anything downloads.
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(false)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `a switched-on daily mix downloads in offline mode`() = runBlocking {
        seedPlaylistAndSnapshot(PlaylistType.DAILY_MIX, "spotify:playlist:dm", "Daily Mix 1", syncEnabled = true)

        buildWorker().doWork()

        coVerify(atLeast = 1) { downloadQueueDao.insertAll(any()) }
    }

    /**
     * The #368 case, expressed by the switch: a mix nobody switched on still
     * links its tracks (Home shows it, Online streams it on tap) and pulls no
     * bytes. Without the link assertion, "skip mixes entirely" would pass.
     */
    @Test
    fun `a switched-off daily mix links its tracks but downloads nothing`() = runBlocking {
        val id = seedPlaylistAndSnapshot(PlaylistType.DAILY_MIX, "spotify:playlist:dm", "Daily Mix 1", syncEnabled = false)

        buildWorker().doWork()

        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
        assertEquals(3, db.playlistDao().getTracksForPlaylist(id).size)
    }

    /** Control: the rule is "the switch decides", not "mixes download". */
    @Test
    fun `custom playlist still enqueues in offline mode`() = runBlocking {
        seedPlaylistAndSnapshot(PlaylistType.CUSTOM, "spotify:playlist:mine", "My Playlist", syncEnabled = true)

        buildWorker().doWork()

        coVerify(atLeast = 1) { downloadQueueDao.insertAll(any()) }
    }

    @Test
    fun `a switched-off custom playlist downloads nothing in offline mode`() = runBlocking {
        seedPlaylistAndSnapshot(PlaylistType.CUSTOM, "spotify:playlist:mine", "My Playlist", syncEnabled = false)

        buildWorker().doWork()

        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
    }

    /** Seeds a local playlist plus a remote snapshot of 3 new tracks; returns the playlist id. */
    private suspend fun seedPlaylistAndSnapshot(
        type: PlaylistType,
        sourceId: String,
        name: String,
        syncEnabled: Boolean,
    ): Long {
        val id = db.playlistDao().insert(
            PlaylistEntity(
                name = name,
                source = MusicSource.SPOTIFY,
                sourceId = sourceId,
                type = type,
                syncEnabled = syncEnabled,
            )
        )

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
        return id
    }

    private fun buildWorker(): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to 1L))
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
                // Undo capture is a safety net, not behaviour under test here —
                // the real DAO from the in-memory DB keeps it honest.
                syncUndoDao = db.syncUndoDao(),
                syncLog = com.stash.core.data.sync.SyncLog(),
                // Done already: these tests are about the diff, not the one-time re-key.
                canonicalRecompute = io.mockk.mockk(relaxed = true),
            )
        })
        .build()
}
