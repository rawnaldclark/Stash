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
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(true)
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

    // ── Stack, per mix (2026-10-29) ──────────────────────────────────────

    /** Stacking: the song the service dropped this week stays, next to this week's three. */
    @Test
    fun `a stacking Daily Mix keeps the songs it brought before`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        val id = seed(PlaylistType.DAILY_MIX, "spotify:playlist:37i9dm1", "Daily Mix 1")
        db.playlistDao().setStackSongs(id, true)
        linkOldSong(id)

        buildWorker().doWork()

        assertEquals(4, db.playlistDao().getTracksForPlaylist(id).size)
    }

    /** The default, even with the account on Accumulate: the mix is what the service gave this time. */
    @Test
    fun `a mix that is not stacking holds only this sync's songs`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        val id = seed(PlaylistType.DAILY_MIX, "spotify:playlist:37i9dm1", "Daily Mix 1")
        linkOldSong(id)

        buildWorker().doWork()

        assertEquals(3, db.playlistDao().getTracksForPlaylist(id).size)
    }

    /**
     * A radio a new install would never offer Stack for, but whose switch is on (it was stacking
     * before the switch existed): the switch is the rule, so it keeps what it collected, and
     * shows the switch to turn it off (review on #737).
     */
    @Test
    fun `a mix whose switch is on stacks, even one that new mixes aren't offered it for`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        val id = seed(PlaylistType.DAILY_MIX, "spotify:playlist:37i9radio", "Beck Radio")
        db.playlistDao().setStackSongs(id, true)
        linkOldSong(id)

        buildWorker().doWork()

        assertEquals(4, db.playlistDao().getTracksForPlaylist(id).size)
        assertEquals(true, com.stash.core.data.sync.MixStacking.showsSwitch(MusicSource.SPOTIFY, PlaylistType.DAILY_MIX, "Beck Radio", stackSongs = true))
    }

    /**
     * Upgrading: the account was accumulating, so its Daily Mix already holds weeks of songs.
     * The first sync under per-mix stacking switches Stack on for every mix the account had,
     * radios too (they were collecting as well), so the update doesn't shrink any of them back
     * to this week's (review on #737).
     */
    @Test
    fun `the first sync after the update keeps an accumulating account's mixes stacking`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(false)
        coEvery { syncPreferencesManager.markMixStackSeeded() } returns Unit
        val mix = seed(PlaylistType.DAILY_MIX, "spotify:playlist:37i9dm1", "Daily Mix 1")
        linkOldSong(mix)
        db.playlistDao().insert(
            PlaylistEntity(name = "Beck Radio", source = MusicSource.SPOTIFY, sourceId = "spotify:playlist:radio", type = PlaylistType.DAILY_MIX),
        )

        buildWorker().doWork()

        assertEquals(true, db.playlistDao().findBySourceId("spotify:playlist:37i9dm1")!!.stackSongs)
        assertEquals(true, db.playlistDao().findBySourceId("spotify:playlist:radio")!!.stackSongs)
        assertEquals(4, db.playlistDao().getTracksForPlaylist(mix).size)
        coVerify(exactly = 1) { syncPreferencesManager.markMixStackSeeded() }
    }

    /** An account set to Refresh chose fresh mixes; the carry-over leaves its mixes alone. */
    @Test
    fun `the carry-over leaves a refreshing account's mixes alone`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(false)
        coEvery { syncPreferencesManager.markMixStackSeeded() } returns Unit
        seed(PlaylistType.DAILY_MIX, "spotify:playlist:37i9dm1", "Daily Mix 1")

        buildWorker().doWork()

        assertEquals(false, db.playlistDao().findBySourceId("spotify:playlist:37i9dm1")!!.stackSongs)
    }

    /** A song a mix brought on an earlier sync, which this sync's snapshot no longer has. */
    private suspend fun linkOldSong(playlistId: Long) {
        val trackId = db.trackDao().insert(
            com.stash.core.data.db.entity.TrackEntity(title = "Last Week", artist = "Old Artist", spotifyUri = "spotify:track:old"),
        )
        db.playlistDao().insertCrossRef(
            com.stash.core.data.db.entity.PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = 9),
        )
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
