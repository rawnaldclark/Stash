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
import com.stash.core.data.db.entity.TrackMergeLogEntity
import com.stash.core.data.library.duplicates.TrackMerger
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After a merge, the next sync finds the merged-away row's ids through `track_aliases` and never
 * makes that row again: the case behind "Merge" holding (plan 2026-11-01 §2 (c) step 5). The real
 * worker on a real database; the merge is the real [TrackMerger].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerMergedAliasTest {

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
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        runBlocking { db.syncHistoryDao().insert(SyncHistoryEntity(id = SYNC_ID)) }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(false)
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(true)
    }

    @After fun tearDown() = db.close()

    @Test fun `a merged-away video in a YouTube playlist finds the kept row and is not made again`() = runBlocking {
        val kept = row("Starting Over", youtubeId = "handVideo01", file = "/music/starting-over.flac")
        val gone = row("Starting Over", youtubeId = "otherVideo1")
        val pid = playlist(MusicSource.YOUTUBE, snapshot("Starting Over", youtubeId = "otherVideo1", position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pid, trackId = gone, position = 0))
        assertNotNull(TrackMerger(db).merge(kept, gone, TrackMergeLogEntity.REASON_USER_MERGE))
        val before = db.trackDao().getById(kept)!!

        buildWorker().doWork()

        assertNull("the merged-away upload has no row again", db.trackDao().findByYoutubeId("otherVideo1"))
        assertEquals("no row was made", 1, count("SELECT COUNT(*) FROM tracks"))
        assertNotNull("the kept row stands in the playlist", db.playlistDao().getCrossRef(pid, kept))
        assertEquals("nothing is downloaded twice", 0, db.downloadQueueDao().getPendingBySyncId(SYNC_ID).size)
        assertEquals("the kept row keeps its own video", before.youtubeId, db.trackDao().getById(kept)!!.youtubeId)
    }

    @Test fun `a merged-away Spotify listing finds the kept row and is not made again`() = runBlocking {
        val kept = row("Starting Over", spotifyUri = "spotify:track:album1", file = "/music/starting-over.flac")
        val gone = row("Starting Over", spotifyUri = "spotify:track:album2")
        val pid = playlist(MusicSource.SPOTIFY, snapshot("Starting Over", spotifyUri = "spotify:track:album2", position = 3))
        assertNotNull(TrackMerger(db).merge(kept, gone, TrackMergeLogEntity.REASON_USER_MERGE))

        buildWorker().doWork()

        assertNull(db.trackDao().findBySpotifyUri("spotify:track:album2"))
        assertEquals(1, count("SELECT COUNT(*) FROM tracks"))
        assertEquals(3, db.playlistDao().getCrossRef(pid, kept)!!.position)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    private suspend fun row(title: String, youtubeId: String? = null, spotifyUri: String? = null, file: String? = null): Long =
        db.trackDao().insert(
            TrackEntity(
                title = title, artist = ARTIST, durationMs = 304_000L, youtubeId = youtubeId, spotifyUri = spotifyUri,
                source = if (spotifyUri != null) MusicSource.SPOTIFY else MusicSource.YOUTUBE,
                filePath = file, isDownloaded = file != null,
                canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(ARTIST),
            ),
        )

    private fun snapshot(title: String, youtubeId: String? = null, spotifyUri: String? = null, position: Int) =
        RemoteTrackSnapshotEntity(
            syncId = SYNC_ID, snapshotPlaylistId = SNAPSHOT_ID, title = title, artist = ARTIST,
            youtubeId = youtubeId, spotifyUri = spotifyUri, durationMs = 304_000L, position = position,
        )

    /** A switched-on playlist of [source] whose fetched snapshot is [tracks]; returns its id. */
    private suspend fun playlist(source: MusicSource, vararg tracks: RemoteTrackSnapshotEntity): Long {
        val sourceId = "PL_$source"
        val id = db.playlistDao().insert(
            PlaylistEntity(name = "Liked", source = source, sourceId = sourceId, type = PlaylistType.CUSTOM, syncEnabled = true),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(SYNC_ID) } returns listOf(
            RemotePlaylistSnapshotEntity(
                id = SNAPSHOT_ID, syncId = SYNC_ID, source = source, sourcePlaylistId = sourceId,
                playlistName = "Liked", playlistType = PlaylistType.CUSTOM,
            ),
        )
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(SNAPSHOT_ID) } returns tracks.toList()
        return id
    }

    private fun buildWorker(): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to SYNC_ID))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = DiffWorker(
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
                canonicalRecompute = mockk(relaxed = true),
            )
        })
        .build()

    private companion object {
        const val SYNC_ID = 1L
        const val SNAPSHOT_ID = 7L
        const val ARTIST = "LSD and the Search for God"
    }
}
