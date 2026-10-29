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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #477, #478: a fresh install opts nothing in — `defaultSyncEnabled` auto-enables
 * only DAILY_MIX — so every user playlist and Liked Songs landed as a row carrying
 * Spotify's `trackCount` with zero tracks inside: "it says it synced, the playlist
 * is empty". DiffWorker threw away the membership it had just fetched over the
 * network for anything not sync-enabled, a skip only ever justified for downloads.
 *
 * In Online mode [shouldEnqueueForDownload] already refuses EVERY type, so linking
 * an un-opted playlist queues nothing and the playlist becomes streamable on tap.
 * Offline mode must keep the opt-in skip, where the flag still decides what lands
 * on disk (#10).
 *
 * Like [DiffWorkerMixNoDownloadTest] these run the WORKER and observe the database.
 * A helper-level test cannot catch this class of bug: it passes whether or not
 * anything calls the helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerOnlineMembershipTest {

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
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(false)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `an un-opted playlist still gets its tracks in online mode`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        val id = seed(PlaylistType.CUSTOM, "spotify:playlist:mine", "My Playlist")

        buildWorker().doWork()

        assertEquals(3, db.playlistDao().getTracksForPlaylist(id).size)
    }

    @Test
    fun `liked songs fills in online mode too`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        val id = seed(PlaylistType.LIKED_SONGS, "spotify:collection:tracks", "Liked Songs")

        buildWorker().doWork()

        assertEquals(3, db.playlistDao().getTracksForPlaylist(id).size)
    }

    /**
     * The point of linking in Online mode is that it costs nothing: no request was
     * added, and nothing may reach the download queue. Without this, "link
     * everything" would quietly become "download everything the user never asked
     * for" — the #10 regression.
     */
    @Test
    fun `an un-opted playlist downloads nothing in online mode`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        seed(PlaylistType.CUSTOM, "spotify:playlist:mine", "My Playlist")

        buildWorker().doWork()

        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
    }

    /**
     * Control: Offline mode must still honour the opt-in, or the Sync Preferences
     * flag means nothing and a fresh install downloads someone's whole Spotify
     * account.
     */
    @Test
    fun `an un-opted playlist stays empty in offline mode`() = runBlocking {
        coEvery { streamingPreference.current() } returns false
        val id = seed(PlaylistType.CUSTOM, "spotify:playlist:mine", "My Playlist")

        buildWorker().doWork()

        assertEquals(0, db.playlistDao().getTracksForPlaylist(id).size)
    }

    /**
     * "Stream everything" on the first-sync sheet: a playlist this sync finds for
     * the first time goes on Home's Your playlists rail, so a new user's account
     * shows up without picking anything. Pinned only, never switched on: in
     * Download mode a switched-on playlist downloads (#368).
     */
    @Test
    fun `a newly found playlist goes on Home after Stream everything`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(true)
        seedRemote(PlaylistType.CUSTOM, "spotify:playlist:new", "New Playlist")

        buildWorker().doWork()

        val row = db.playlistDao().findBySourceId("spotify:playlist:new")!!
        assertNotNull(row.pinnedToHomeAt)
        assertEquals(false, row.syncEnabled)
        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
    }

    /** Everyone who didn't pick it (every upgrading install) keeps Home as they arranged it. */
    @Test
    fun `a newly found playlist stays off Home without Stream everything`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        seedRemote(PlaylistType.CUSTOM, "spotify:playlist:new", "New Playlist")

        buildWorker().doWork()

        assertNull(db.playlistDao().findBySourceId("spotify:playlist:new")!!.pinnedToHomeAt)
    }

    /** Download mode: a pinned playlist would sit on Home with nothing on the phone. */
    @Test
    fun `a newly found playlist stays off Home in Download mode`() = runBlocking {
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(true)
        seedRemote(PlaylistType.CUSTOM, "spotify:playlist:new", "New Playlist")

        buildWorker().doWork()

        assertNull(db.playlistDao().findBySourceId("spotify:playlist:new")!!.pinnedToHomeAt)
    }

    /** Mixes have their own Home rails; pinning one would also make it lead its rail forever. */
    @Test
    fun `a newly found mix is not pinned`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(true)
        seedRemote(PlaylistType.DAILY_MIX, "spotify:playlist:37i9mix", "Daily Mix 1")

        buildWorker().doWork()

        assertNull(db.playlistDao().findBySourceId("spotify:playlist:37i9mix")!!.pinnedToHomeAt)
    }

    /**
     * Taken out of the library from the account's playlist page: the account
     * still has it, but sync neither brings it back nor links its songs.
     */
    @Test
    fun `a playlist taken out of the library stays out after a sync`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        val id = seed(PlaylistType.CUSTOM, "spotify:playlist:mine", "My Playlist")
        db.playlistDao().setUserHidden(id, true)

        buildWorker().doWork()

        val row = db.playlistDao().findBySourceId("spotify:playlist:mine")!!
        assertEquals(false, row.isActive)
        assertEquals(true, row.userHidden)
        assertEquals(0, db.playlistDao().getTracksForPlaylist(id).size)
    }

    /**
     * Seeds a NOT-sync-enabled local playlist (what a fresh install produces for
     * everything but a mix) plus a remote snapshot of 3 fetched tracks.
     */
    private suspend fun seed(
        type: PlaylistType,
        sourceId: String,
        name: String,
    ): Long {
        val id = db.playlistDao().insert(
            PlaylistEntity(
                name = name,
                source = MusicSource.SPOTIFY,
                sourceId = sourceId,
                type = type,
                trackCount = 3,
                syncEnabled = false,
            )
        )
        seedRemote(type, sourceId, name)
        return id
    }

    /** A remote snapshot of 3 fetched tracks, with no local playlist yet unless [seed] made one. */
    private fun seedRemote(type: PlaylistType, sourceId: String, name: String) {
        val snapshotId = 7L
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(
            RemotePlaylistSnapshotEntity(
                id = snapshotId,
                syncId = 1L,
                source = MusicSource.SPOTIFY,
                sourcePlaylistId = sourceId,
                playlistName = name,
                playlistType = type,
                trackCount = 3,
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
                syncUndoDao = db.syncUndoDao(),
                syncLog = com.stash.core.data.sync.SyncLog(),
                // Done already: these tests are about the diff, not the one-time re-key.
                canonicalRecompute = io.mockk.mockk(relaxed = true),
            )
        })
        .build()
}
