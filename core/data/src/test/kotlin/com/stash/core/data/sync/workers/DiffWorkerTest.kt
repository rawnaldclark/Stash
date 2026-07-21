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
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Covers the correctness-sensitive paths in DiffWorker's batched rewrite:
 * REFRESH must not resurrect soft-deleted tracks, ACCUMULATE must preserve
 * addedAt, and the bulk identity match must honor spotifyUri > youtubeId >
 * canonical priority when a snapshot could match multiple candidates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerTest {

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
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
    }

    @After
    fun tearDown() { db.close() }

    // ── REFRESH must not resurrect a soft-deleted track ──────────────────

    @Test
    fun `REFRESH does not resurrect a soft-deleted track that reappears in the snapshot`() = runBlocking {
        val track = TrackEntity(
            title = "Runaway", artist = "Kanye West",
            canonicalTitle = "runaway", canonicalArtist = "kanye west",
            spotifyUri = "spotify:track:abc",
        )
        val trackId = db.trackDao().insert(track)

        val playlist = PlaylistEntity(
            name = "Liked Songs", source = MusicSource.SPOTIFY,
            sourceId = "spotify:collection:tracks", type = PlaylistType.LIKED_SONGS,
            syncEnabled = true,
        )
        val playlistId = db.playlistDao().insert(playlist)

        // User previously removed this track — tombstone row.
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId, trackId = trackId, position = 0,
                addedAt = Instant.parse("2025-01-01T00:00:00Z"),
                removedAt = Instant.parse("2025-06-01T00:00:00Z"),
            )
        )

        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)

        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 1L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:collection:tracks",
            playlistName = "Liked Songs", playlistType = PlaylistType.LIKED_SONGS,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(1L) } returns listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L, snapshotPlaylistId = 1L,
                title = "Runaway", artist = "Kanye West",
                spotifyUri = "spotify:track:abc", position = 0,
            )
        )

        buildWorker().doWork()

        val crossRefs = db.playlistDao().getCrossRefsForPlaylist(playlistId)
        assertEquals("expected exactly one cross-ref row (the tombstone)", 1, crossRefs.size)
        assertTrue("tombstone must stay soft-deleted, not resurrected", crossRefs.single().removedAt != null)

        val visibleTracks = db.playlistDao().getTracksForPlaylist(playlistId)
        assertTrue("soft-deleted track must not appear in visible playlist tracks", visibleTracks.isEmpty())
    }

    // ── ACCUMULATE preserves addedAt on re-stamped rows ───────────────────

    @Test
    fun `ACCUMULATE preserves original addedAt when a track is re-synced`() = runBlocking {
        val track = TrackEntity(
            title = "Borderline", artist = "Tame Impala",
            canonicalTitle = "borderline", canonicalArtist = "tame impala",
            spotifyUri = "spotify:track:xyz",
        )
        val trackId = db.trackDao().insert(track)

        val playlist = PlaylistEntity(
            name = "Discover Weekly", source = MusicSource.SPOTIFY,
            sourceId = "spotify:playlist:dw", type = PlaylistType.DAILY_MIX,
            syncEnabled = true,
        )
        val playlistId = db.playlistDao().insert(playlist)

        val originalAddedAt = Instant.parse("2024-03-01T00:00:00Z")
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId, trackId = trackId, position = 3,
                addedAt = originalAddedAt, removedAt = null,
            )
        )

        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)

        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 2L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:dw",
            playlistName = "Discover Weekly", playlistType = PlaylistType.DAILY_MIX,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(2L) } returns listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L, snapshotPlaylistId = 2L,
                title = "Borderline", artist = "Tame Impala",
                spotifyUri = "spotify:track:xyz", position = 0,
            )
        )

        buildWorker().doWork()

        val crossRef = db.playlistDao().getCrossRef(playlistId, trackId)
        assertEquals(originalAddedAt, crossRef?.addedAt)
    }

    // ── Batch identity match priority: spotifyUri > youtubeId > canonical ─

    @Test
    fun `matches by spotifyUri even when youtubeId and canonical also match different rows`() = runBlocking {
        val trackDao = db.trackDao()
        val bySpotify = trackDao.insert(
            TrackEntity(title = "T1", artist = "A1", spotifyUri = "spotify:track:winner",
                canonicalTitle = "unrelated1", canonicalArtist = "unrelated1")
        )
        trackDao.insert(
            TrackEntity(title = "T2", artist = "A2", youtubeId = "yt-loser",
                canonicalTitle = "unrelated2", canonicalArtist = "unrelated2")
        )
        trackDao.insert(
            TrackEntity(title = "SongA", artist = "ArtistA",
                canonicalTitle = "songa", canonicalArtist = "artista")
        )

        val playlist = PlaylistEntity(
            name = "P", source = MusicSource.SPOTIFY, sourceId = "spotify:playlist:p",
            syncEnabled = true,
        )
        val playlistId = db.playlistDao().insert(playlist)

        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 3L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:p", playlistName = "P",
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(3L) } returns listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L, snapshotPlaylistId = 3L,
                title = "SongA", artist = "ArtistA",
                spotifyUri = "spotify:track:winner", youtubeId = "yt-loser", position = 0,
            )
        )
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)

        val result = buildWorker().doWork()
        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)

        val crossRefs = db.playlistDao().getCrossRefsForPlaylist(playlistId)
        assertEquals(1, crossRefs.size)
        assertEquals("spotifyUri match must win", bySpotify, crossRefs.single().trackId)
        assertEquals("no new track should be inserted — matched existing", 3, trackDao.getAllForIntegrityScan().size)
    }

    @Test
    fun `LIKED_SONGS backfills missing art from the snapshot`() = runBlocking {
        assertEquals(
            "https://cdn.example/new-cover.jpg",
            syncLikedPlaylistArt(existingArt = "  ", snapshotArt = "https://cdn.example/new-cover.jpg"),
        )
    }

    @Test
    fun `LIKED_SONGS preserves an existing nonblank cover`() = runBlocking {
        assertEquals(
            "https://cdn.example/established-cover.jpg",
            syncLikedPlaylistArt(
                existingArt = "https://cdn.example/established-cover.jpg",
                snapshotArt = "https://cdn.example/new-cover.jpg",
            ),
        )
    }

    private suspend fun syncLikedPlaylistArt(existingArt: String?, snapshotArt: String?): String? {
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Liked Songs",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify_liked_songs",
                type = PlaylistType.LIKED_SONGS,
                artUrl = existingArt,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 4L,
            syncId = 1L,
            source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify_liked_songs",
            playlistName = "Liked Songs",
            playlistType = PlaylistType.LIKED_SONGS,
            artUrl = snapshotArt,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(4L) } returns emptyList()

        val result = buildWorker().doWork()

        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)
        return db.playlistDao().getById(playlistId)?.artUrl
    }

    // ── #343: REFRESH must never mirror-clear against unreliable fetches ──

    private suspend fun seedSyncedPlaylist(
        snapshotId: String? = "old-snap",
    ): Pair<Long, Long> {
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Keeper", artist = "Artist",
                canonicalTitle = "keeper", canonicalArtist = "artist",
                spotifyUri = "spotify:track:keeper",
            )
        )
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Jams", source = MusicSource.SPOTIFY,
                sourceId = "pl1", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = 0)
        )
        snapshotId?.let { db.playlistDao().updateSnapshotId(playlistId, it) }
        db.playlistDao().updateTrackCount(playlistId, 1)
        return playlistId to trackId
    }

    private fun stubSnapshot(
        partial: Boolean,
        listedTrackCount: Int,
        trackSnapshots: List<RemoteTrackSnapshotEntity> = emptyList(),
    ) {
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 4L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "pl1", playlistName = "Jams",
            playlistType = PlaylistType.CUSTOM, trackCount = listedTrackCount,
            partial = partial, snapshotId = "new-snap",
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(4L) } returns trackSnapshots
    }

    private suspend fun activeTrackCount(playlistId: Long): Int =
        db.playlistDao().getCrossRefsForPlaylist(playlistId).count { it.removedAt == null }

    @Test
    fun `REFRESH keeps local tracks when the snapshot is marked partial`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(partial = true, listedTrackCount = 12)

        val result = buildWorker().doWork()

        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("local track survives a partial fetch", 1, activeTrackCount(playlistId))
    }

    @Test
    fun `REFRESH keeps local tracks when the snapshot is empty but the listing claims tracks`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(partial = false, listedTrackCount = 12)

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("unmarked failure shape must not clear", 1, activeTrackCount(playlistId))
    }

    @Test
    fun `REFRESH mirrors a genuinely emptied playlist`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(partial = false, listedTrackCount = 0)

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("clean empty fetch mirrors the empty remote", 0, activeTrackCount(playlistId))
    }

    @Test
    fun `partial fetch does not advance snapshot id or track count`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist(snapshotId = "old-snap")
        stubSnapshot(partial = true, listedTrackCount = 12)

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(
            "snapshot id must stay stale so the next sync re-diffs this playlist",
            "old-snap", db.playlistDao().getSnapshotId(playlistId),
        )
        assertEquals("track count must not lie over retained tracks", 1, db.playlistDao().getById(playlistId)?.trackCount)
    }

    @Test
    fun `partial fetch still merges the additions it did return`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(
            partial = true, listedTrackCount = 12,
            trackSnapshots = listOf(
                RemoteTrackSnapshotEntity(
                    id = 9L, syncId = 1L, snapshotPlaylistId = 4L,
                    title = "Newcomer", artist = "Artist",
                    durationMs = 200_000, spotifyUri = "spotify:track:new",
                    position = 0,
                )
            ),
        )

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("existing track kept AND the fetched addition merged", 2, activeTrackCount(playlistId))
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
            )
        })
        .build()
}
