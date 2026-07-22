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
import com.stash.core.model.DownloadStatus
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
    fun `duplicate new Spotify URI reuses one track and keeps the last playlist position`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Duplicates", source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:duplicates", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 5L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:duplicates",
            playlistName = "Duplicates", playlistType = PlaylistType.CUSTOM,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(5L) } returns listOf(
            RemoteTrackSnapshotEntity(
                id = 51L, syncId = 1L, snapshotPlaylistId = 5L,
                title = "Repeated Song", artist = "Artist",
                spotifyUri = "spotify:track:repeat", position = 1,
            ),
            RemoteTrackSnapshotEntity(
                id = 52L, syncId = 1L, snapshotPlaylistId = 5L,
                title = "Provider Renamed Song", artist = "Different Artist",
                spotifyUri = "spotify:track:repeat", position = 7,
            ),
        )
        val result = buildWorker().doWork()

        assertTrue("duplicate provider identities must not fail the diff", result is androidx.work.ListenableWorker.Result.Success)
        val tracks = db.trackDao().getAllForIntegrityScan()
        assertEquals("one provider identity creates one track row", 1, tracks.size)
        assertEquals("spotify:track:repeat", tracks.single().spotifyUri)
        val crossRefs = db.playlistDao().getCrossRefsForPlaylist(playlistId)
        assertEquals("one track has one membership row in the current schema", 1, crossRefs.size)
        assertEquals("duplicate membership keeps the existing last-occurrence behavior", 7, crossRefs.single().position)
        assertEquals("only one new track is reported", 1, result.outputData.getInt(DiffWorker.KEY_NEW_TRACKS, -1))
        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
    }

    @Test
    fun `duplicate new YouTube ID queues one offline download`() = runBlocking {
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "YouTube Duplicates", source = MusicSource.YOUTUBE,
                sourceId = "youtube:playlist:duplicates", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 6L, syncId = 1L, source = MusicSource.YOUTUBE,
            sourcePlaylistId = "youtube:playlist:duplicates",
            playlistName = "YouTube Duplicates", playlistType = PlaylistType.CUSTOM,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(6L) } returns listOf(
            RemoteTrackSnapshotEntity(
                id = 61L, syncId = 1L, snapshotPlaylistId = 6L,
                title = "Repeated Video", artist = "Artist",
                youtubeId = "repeat-video", position = 2,
            ),
            RemoteTrackSnapshotEntity(
                id = 62L, syncId = 1L, snapshotPlaylistId = 6L,
                title = "Provider Renamed Video", artist = "Different Artist",
                youtubeId = "repeat-video", position = 9,
            ),
        )

        val result = buildWorker().doWork()

        assertTrue("duplicate YouTube identities must not fail the diff", result is androidx.work.ListenableWorker.Result.Success)
        val storedTrack = db.trackDao().getAllForIntegrityScan().single()
        assertEquals(9, db.playlistDao().getCrossRefsForPlaylist(playlistId).single().position)
        assertEquals(1, result.outputData.getInt(DiffWorker.KEY_NEW_TRACKS, -1))
        coVerify(exactly = 1) {
            downloadQueueDao.insertAll(match { entries ->
                entries.size == 1 &&
                    entries.single().trackId == storedTrack.id &&
                    entries.single().syncId == 1L &&
                    entries.single().status == DownloadStatus.PENDING &&
                    entries.single().youtubeUrl == "https://music.youtube.com/watch?v=repeat-video"
            })
        }
    }

    @Test
    fun `canonical-only duplicates create one offline track and queue entry`() = runBlocking {
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Canonical Duplicates", source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:canonical-duplicates", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 10L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:canonical-duplicates",
            playlistName = "Canonical Duplicates", playlistType = PlaylistType.CUSTOM,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(10L) } returns listOf(
            RemoteTrackSnapshotEntity(
                id = 101L, syncId = 1L, snapshotPlaylistId = 10L,
                title = "Canonical Song", artist = "The Artist", position = 4,
            ),
            RemoteTrackSnapshotEntity(
                id = 102L, syncId = 1L, snapshotPlaylistId = 10L,
                title = "Canonical Song", artist = "The Artist", position = 6,
            ),
        )

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(1, db.trackDao().getAllForIntegrityScan().size)
        assertEquals(6, db.playlistDao().getCrossRefsForPlaylist(playlistId).single().position)
        assertEquals(1, result.outputData.getInt(DiffWorker.KEY_NEW_TRACKS, -1))
        coVerify(exactly = 1) {
            downloadQueueDao.insertAll(match { it.size == 1 })
        }
    }

    @Test
    fun `blank provider IDs are stored as missing and do not merge unrelated tracks`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Blank IDs", source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:blank-ids", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 7L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:blank-ids",
            playlistName = "Blank IDs", playlistType = PlaylistType.CUSTOM,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(7L) } returns listOf(
            RemoteTrackSnapshotEntity(
                id = 71L, syncId = 1L, snapshotPlaylistId = 7L,
                title = "First Song", artist = "First Artist",
                spotifyUri = " ", youtubeId = "", position = 0,
            ),
            RemoteTrackSnapshotEntity(
                id = 72L, syncId = 1L, snapshotPlaylistId = 7L,
                title = "Second Song", artist = "Second Artist",
                spotifyUri = "", youtubeId = " ", position = 1,
            ),
        )

        val result = buildWorker().doWork()
        val tracks = db.trackDao().getAllForIntegrityScan()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("different canonical identities stay distinct", 2, tracks.size)
        assertTrue(tracks.all { it.spotifyUri == null && it.youtubeId == null })
    }

    @Test
    fun `canonical identity keeps title and artist delimiters separate`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Delimiter IDs", source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:delimiter-ids", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 9L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:delimiter-ids",
            playlistName = "Delimiter IDs", playlistType = PlaylistType.CUSTOM,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(9L) } returns listOf(
            RemoteTrackSnapshotEntity(
                id = 91L, syncId = 1L, snapshotPlaylistId = 9L,
                title = "A|B", artist = "C", position = 0,
            ),
            RemoteTrackSnapshotEntity(
                id = 92L, syncId = 1L, snapshotPlaylistId = 9L,
                title = "A", artist = "B|C", position = 1,
            ),
        )

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(
            "canonical pairs must not collide through their query-string encoding",
            2,
            db.trackDao().getAllForIntegrityScan().size,
        )
    }

    @Test
    fun `canonical query false positive is rejected by the typed in-memory key`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val existingTrackId = db.trackDao().insert(
            TrackEntity(
                title = "A|B", artist = "C",
                canonicalTitle = "a|b", canonicalArtist = "c",
            )
        )
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Delimiter Candidate", source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:delimiter-candidate", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 11L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:delimiter-candidate",
            playlistName = "Delimiter Candidate", playlistType = PlaylistType.CUSTOM,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(11L) } returns listOf(
            RemoteTrackSnapshotEntity(
                id = 111L, syncId = 1L, snapshotPlaylistId = 11L,
                title = "A", artist = "B|C", position = 0,
            )
        )

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(2, db.trackDao().getAllForIntegrityScan().size)
        val linkedTrackId = db.playlistDao().getCrossRefsForPlaylist(playlistId).single().trackId
        assertTrue("the delimiter collision must not select the existing row", linkedTrackId != existingTrackId)
    }

    @Test
    fun `duplicate existing track keeps first YouTube ID final artwork and original addedAt`() = runBlocking {
        coEvery { streamingPreference.current() } returns true
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Mutable", artist = "Artist",
                spotifyUri = "spotify:track:mutable",
                albumArtUrl = "https://cdn.example/original.jpg",
                canonicalTitle = "mutable", canonicalArtist = "artist",
            )
        )
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Mutable State", source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:mutable", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val originalAddedAt = Instant.parse("2024-01-01T00:00:00Z")
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId, trackId = trackId, position = 0,
                addedAt = originalAddedAt,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 8L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:mutable",
            playlistName = "Mutable State", playlistType = PlaylistType.CUSTOM,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(8L) } returns listOf(
            RemoteTrackSnapshotEntity(
                id = 81L, syncId = 1L, snapshotPlaylistId = 8L,
                title = "Mutable", artist = "Artist",
                spotifyUri = "spotify:track:mutable", youtubeId = "first-video",
                albumArtUrl = "https://cdn.example/alternate.jpg", position = 3,
            ),
            RemoteTrackSnapshotEntity(
                id = 82L, syncId = 1L, snapshotPlaylistId = 8L,
                title = "Mutable", artist = "Artist",
                spotifyUri = "spotify:track:mutable", youtubeId = "second-video",
                albumArtUrl = "https://cdn.example/original.jpg", position = 8,
            ),
        )

        val result = buildWorker().doWork()
        val storedTrack = db.trackDao().getById(trackId)
        val membership = db.playlistDao().getCrossRef(playlistId, trackId)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("first nonblank YouTube ID wins", "first-video", storedTrack?.youtubeId)
        assertEquals("last nonblank artwork wins", "https://cdn.example/original.jpg", storedTrack?.albumArtUrl)
        assertEquals(originalAddedAt, membership?.addedAt)
        assertEquals(8, membership?.position)
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
