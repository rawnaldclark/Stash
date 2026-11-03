package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.album.SavedAlbums
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SpotifySavedAlbumsFetcher
import com.stash.core.data.sync.SyncLog
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.data.spotify.SpotifyAlbumTrack
import com.stash.data.spotify.SpotifyAlbumTracklist
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifySavedAlbum
import com.stash.data.spotify.SpotifySavedAlbumsPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Spotify saved album end to end, the saved-albums step then the diff, over one in-memory
 * database with Spotify faked: it lands as an album (Library › Albums, not Playlists) with its songs
 * in the album's order, nothing downloads, and the next sync passes it on without reading it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerSpotifySavedAlbumsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val spotify = mockk<SpotifyApiClient>()
    private val syncStateManager = mockk<SyncStateManager>(relaxed = true)
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val blocklistGuard = mockk<BlocklistGuard>()
    private val streamingPreference = mockk<StreamingPreference>()
    private val syncLog = SyncLog()

    private val albumId = "FakeAlb1xxxxxxxxxxxxxx"
    private val key = SavedAlbums.sourceId("spotify", albumId)
    private val album = SpotifySavedAlbum(
        id = albumId, name = "Album 1", artists = listOf("Artist 1"),
        coverUrl = "https://i.scdn.co/image/ab67616d0000b273fake", addedAt = "2026-10-11T10:00:00Z", albumType = "ALBUM",
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        // Download mode: the one where a switched-off playlist isn't linked.
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(true)
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(true)
        every { syncPreferencesManager.syncSpotifySavedAlbums } returns flowOf(true)

        coEvery { spotify.getSavedAlbumsPage(any(), 0, any()) } returns
            SpotifySavedAlbumsPage(listOf(album), listOf(albumId), 1, 1, 0)
        coEvery { spotify.getAlbumTracks(albumId) } returns SpotifyAlbumTracklist(
            tracks = listOf("Intro", "Second Song", "Closer").mapIndexed { i, title ->
                SpotifyAlbumTrack("FakeTra${i + 1}".padEnd(22, 'x'), title, listOf("Artist 1"), 180_000L + i, false, 1, i + 1)
            },
            totalCount = 3,
            complete = true,
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun sync(): Long {
        val syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        SpotifySavedAlbumsFetcher(spotify, db, syncPreferencesManager, db.syncUndoDao(), syncLog).fetch(syncId, SyncMode.REFRESH)
        val result = buildWorker(syncId).doWork()
        assertTrue("diff must succeed: $result", result is ListenableWorker.Result.Success)
        return syncId
    }

    @Test fun `a saved album lands as an album, in its order, switched off, and nothing downloads`() = runBlocking {
        val syncId = sync()

        val row = db.playlistDao().findBySourceId(key)!!
        assertEquals(MusicSource.SPOTIFY, row.source)
        assertEquals(PlaylistType.CUSTOM, row.type)
        assertEquals("Album 1", row.name)
        assertTrue(row.isActive)
        assertFalse("nothing downloads until the listener says so", row.syncEnabled)
        assertNull("an album is never pinned to Home, even with new playlists pinned", row.pinnedToHomeAt)
        assertEquals(
            "linked in Download mode, though switched off",
            listOf("Intro", "Second Song", "Closer"),
            db.playlistDao().getTracksForPlaylist(row.id).map { it.title },
        )
        assertEquals(3, row.let { db.playlistDao().getById(it.id)!!.trackCount })
        assertTrue(db.downloadQueueDao().getPendingBySyncId(syncId).isEmpty())

        val albums = db.playlistDao().observeSavedAlbums().first()
        assertEquals("in Library › Albums", listOf(row.id), albums.map { it.id })
        assertEquals(
            "stamped with the save it mirrors",
            "added:2026-10-11T10:00:00Z",
            SpotifyMirrorStamp.decode(db.playlistDao().getSnapshotId(row.id))!!.revision,
        )
    }

    @Test fun `the next sync passes the album on without reading it, and leaves it as it was`() = runBlocking {
        sync()
        val row = db.playlistDao().findBySourceId(key)!!
        val before = db.playlistDao().getTracksForPlaylist(row.id).map { it.id }
        val stamp = db.playlistDao().getSnapshotId(row.id)

        sync()

        coVerify(exactly = 1) { spotify.getAlbumTracks(albumId) }
        assertEquals(before, db.playlistDao().getTracksForPlaylist(row.id).map { it.id })
        assertEquals(stamp, db.playlistDao().getSnapshotId(row.id))
    }

    @Test fun `un-saved, then saved again on Spotify, the album comes back`() = runBlocking {
        sync()
        val row = db.playlistDao().findBySourceId(key)!!

        coEvery { spotify.getSavedAlbumsPage(any(), 0, any()) } returns SpotifySavedAlbumsPage(emptyList(), emptyList(), 0, 0, 0)
        sync()
        assertFalse(db.playlistDao().getById(row.id)!!.isActive)

        coEvery { spotify.getSavedAlbumsPage(any(), 0, any()) } returns SpotifySavedAlbumsPage(
            listOf(album.copy(addedAt = "2026-11-02T08:00:00Z")), listOf(albumId), 1, 1, 0,
        )
        sync()
        val back = db.playlistDao().getById(row.id)!!
        assertTrue(back.isActive)
        assertEquals(3, db.playlistDao().getTracksForPlaylist(row.id).size)
    }

    @Test fun `a pasted album the listener removed stays removed through a sync, though Spotify still has it saved`() = runBlocking {
        // #815's Remove used to delete a pasted copy, freeing its key: the sync then made a new row.
        val pasted = db.playlistDao().insert(
            PlaylistEntity(name = "Album 1", source = MusicSource.BOTH, sourceId = key, type = PlaylistType.CUSTOM),
        )
        db.playlistDao().setUserHidden(pasted, true)

        sync()

        val row = db.playlistDao().findBySourceId(key)!!
        assertEquals(pasted, row.id)
        assertTrue(row.userHidden)
        assertFalse(row.isActive)
        assertTrue("not in Library › Albums", db.playlistDao().observeSavedAlbums().first().isEmpty())
        coVerify(exactly = 0) { spotify.getAlbumTracks(any()) }
    }

    @Test fun `a switched-off YouTube Music album keeps Download mode's skip, as it carries no stamp`() = runBlocking {
        val syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        val youTube = db.playlistDao().insert(
            PlaylistEntity(name = "Other Album", source = MusicSource.YOUTUBE, sourceId = "MPREb_fake", type = PlaylistType.CUSTOM),
        )
        val snapshot = db.remoteSnapshotDao().insertPlaylistSnapshot(
            RemotePlaylistSnapshotEntity(
                syncId = syncId, source = MusicSource.YOUTUBE, sourcePlaylistId = "MPREb_fake",
                playlistName = "Other Album", trackCount = 1,
            ),
        )
        db.remoteSnapshotDao().insertTrackSnapshots(
            listOf(
                RemoteTrackSnapshotEntity(
                    syncId = syncId, snapshotPlaylistId = snapshot, title = "Song", artist = "Artist",
                    durationMs = 200_000, youtubeId = "vid00000001", position = 0,
                ),
            ),
        )

        val result = buildWorker(syncId).doWork()

        assertTrue("diff must succeed: $result", result is ListenableWorker.Result.Success)
        assertTrue(db.playlistDao().getTracksForPlaylist(youTube).isEmpty())
    }

    private fun buildWorker(syncId: Long): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to syncId))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = DiffWorker(
                appContext, workerParameters,
                database = db,
                remoteSnapshotDao = db.remoteSnapshotDao(),
                trackDao = db.trackDao(),
                playlistDao = db.playlistDao(),
                downloadQueueDao = db.downloadQueueDao(),
                syncHistoryDao = db.syncHistoryDao(),
                trackMatcher = TrackMatcher(),
                syncStateManager = syncStateManager,
                musicRepository = mockk<MusicRepository>(relaxed = true),
                syncPreferencesManager = syncPreferencesManager,
                blocklistGuard = blocklistGuard,
                streamingPreference = streamingPreference,
                syncUndoDao = db.syncUndoDao(),
                syncLog = syncLog,
                canonicalRecompute = mockk(relaxed = true),
            )
        })
        .build()
}
