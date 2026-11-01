package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.common.TrackCanonical
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sync's new song that is already on the phone as another row is linked, not downloaded again
 * (duplicates, cause 4). The case: a song downloaded under one video (by hand, or from Spotify),
 * and a YouTube playlist holding another upload of it. The two ids keep them two rows, which is
 * right, but with lossless on the second row downloaded the same recording again.
 *
 * Runs the real worker on a real DB, with the real download queue: what is queued and what is
 * linked are read back from the tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerLibraryCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val remoteSnapshotDao = mockk<com.stash.core.data.db.dao.RemoteSnapshotDao>()
    private val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true)
    private val syncStateManager = mockk<SyncStateManager>(relaxed = true)
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val blocklistGuard = mockk<BlocklistGuard>()
    private val streamingPreference = mockk<StreamingPreference>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // The sync run the queued rows belong to (download_queue.sync_id is a foreign key).
        runBlocking { db.syncHistoryDao().insert(SyncHistoryEntity(id = SYNC_ID)) }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { streamingPreference.current() } returns false // Download mode: the sync downloads.
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(false)
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(true)
    }

    @After fun tearDown() = db.close()

    @Test fun `a new song already downloaded under another video is linked where it sits, not queued`() = runBlocking {
        val copy = downloaded("Starting Over", youtubeId = "handVideo01", lengthMs = 304_000L)
        val pid = youtubePlaylist(
            snapshot("Never Heard", "newVideo001", position = 0),
            snapshot("Starting Over (Official Audio)", "otherVideo1", position = 1, lengthMs = 305_000L),
        )

        buildWorker().doWork()

        val queued = db.downloadQueueDao().getPendingBySyncId(SYNC_ID).map { db.trackDao().getById(it.trackId)!!.title }
        assertEquals("only the song not on the phone is downloaded", listOf("Never Heard"), queued)

        val link = db.playlistDao().getCrossRef(pid, copy)
        assertNotNull("the phone's copy is in the playlist", link)
        assertEquals("where the song sits", 1, link!!.position)

        // The new row is still made, with its own video, for the next sync to find: nothing is merged.
        val newRow = db.trackDao().findByYoutubeId("otherVideo1")
        assertNotNull(newRow)
        assertFalse(newRow!!.isDownloaded)
        assertNotNull(db.playlistDao().getCrossRef(pid, newRow.id))
        assertEquals("/music/starting-over.flac", db.trackDao().getById(copy)!!.filePath)
    }

    @Test fun `another version or a clearly different length is downloaded as before`() = runBlocking {
        val copy = downloaded("Starting Over", youtubeId = "handVideo01", lengthMs = 304_000L)
        val pid = youtubePlaylist(
            snapshot("Starting Over (Live)", "liveVideo01", position = 0, lengthMs = 304_000L),
            snapshot("Starting Over", "longVideo01", position = 1, lengthMs = 480_000L),
        )

        buildWorker().doWork()

        assertEquals(2, db.downloadQueueDao().getPendingBySyncId(SYNC_ID).size)
        assertNull(db.playlistDao().getCrossRef(pid, copy))
    }

    @Test fun `a copy the user took out of the playlist is not linked back, and the song is not downloaded twice`() = runBlocking {
        val copy = downloaded("Starting Over", youtubeId = "handVideo01", lengthMs = 304_000L)
        val pid = youtubePlaylist(snapshot("Starting Over", "otherVideo1", position = 0, lengthMs = 304_000L))
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = pid, trackId = copy, position = 5, removedAt = java.time.Instant.now()),
        )

        buildWorker().doWork()

        assertEquals(0, db.downloadQueueDao().getPendingBySyncId(SYNC_ID).size)
        assertNotNull("still removed", db.playlistDao().getCrossRef(pid, copy)!!.removedAt)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private suspend fun downloaded(title: String, youtubeId: String, lengthMs: Long): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = ARTIST, durationMs = lengthMs, youtubeId = youtubeId,
            source = MusicSource.YOUTUBE, filePath = "/music/starting-over.flac", isDownloaded = true,
            canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(ARTIST),
        ),
    )

    private fun snapshot(title: String, youtubeId: String, position: Int, lengthMs: Long = 200_000L) =
        RemoteTrackSnapshotEntity(
            syncId = SYNC_ID, snapshotPlaylistId = SNAPSHOT_ID, title = title, artist = ARTIST,
            youtubeId = youtubeId, durationMs = lengthMs, position = position,
        )

    /** A switched-on YouTube playlist whose fetched snapshot is [tracks]; returns its id. */
    private suspend fun youtubePlaylist(vararg tracks: RemoteTrackSnapshotEntity): Long {
        val sourceId = "PLyoutube"
        val id = db.playlistDao().insert(
            PlaylistEntity(name = "Liked", source = MusicSource.YOUTUBE, sourceId = sourceId, type = PlaylistType.CUSTOM, syncEnabled = true),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(SYNC_ID) } returns listOf(
            RemotePlaylistSnapshotEntity(
                id = SNAPSHOT_ID, syncId = SYNC_ID, source = MusicSource.YOUTUBE, sourcePlaylistId = sourceId,
                playlistName = "Liked", playlistType = PlaylistType.CUSTOM,
            ),
        )
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(SNAPSHOT_ID) } returns tracks.toList()
        return id
    }

    private fun buildWorker(): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to SYNC_ID))
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
                downloadQueueDao = db.downloadQueueDao(),
                syncHistoryDao = syncHistoryDao,
                trackMatcher = TrackMatcher(),
                syncStateManager = syncStateManager,
                musicRepository = musicRepository,
                syncPreferencesManager = syncPreferencesManager,
                blocklistGuard = blocklistGuard,
                streamingPreference = streamingPreference,
                syncUndoDao = db.syncUndoDao(),
                syncLog = com.stash.core.data.sync.SyncLog(),
                canonicalRecompute = io.mockk.mockk(relaxed = true),
            )
        })
        .build()

    private companion object {
        const val SYNC_ID = 1L
        const val SNAPSHOT_ID = 7L
        const val ARTIST = "LSD and the Search for God"
    }
}
