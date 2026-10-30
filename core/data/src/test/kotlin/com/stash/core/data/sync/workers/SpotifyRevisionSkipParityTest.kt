package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.core.model.SyncResult
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyLibraryPage
import com.stash.data.spotify.SpotifyPlaylistTracks
import com.stash.data.spotify.model.SpotifyArtist
import com.stash.data.spotify.model.SpotifyOwner
import com.stash.data.spotify.model.SpotifyPlaylistItem
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTrackObject
import com.stash.data.spotify.model.SpotifyTracksRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bar for skipping an unchanged Spotify playlist (see [SpotifyMirrorStamp]):
 * the library ends up exactly as a full sync would leave it, minus the network.
 *
 * Each case builds the same library twice, from the same seed and the same
 * remote, then runs one more sync: (a) as it runs for real, free to skip, and
 * (b) forced to read every playlist in full. `playlist_tracks`, `tracks` and
 * `download_queue` must come out identical. A perturbation between the last
 * sync and this one is what a skip could miss; each is something a full sync
 * reads and the stamp must therefore notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifyRevisionSkipParityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** One library and its fake Spotify. The remote can change between syncs. */
    private inner class Library {
        val db: StashDatabase = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries().build()
        val spotify = mockk<SpotifyApiClient>()
        val prefs = mockk<SyncPreferencesManager>()
        var mode = SyncMode.REFRESH
        var revision = "AAAAAMv6SY4RNBP4lRibleC3UcWweCUH"
        var remoteTitles = listOf("A", "B")

        init {
            every { prefs.discoverAutoMixes } returns flowOf(false)
            // The sync tab overhaul's settings: mixes already seeded, nothing pinned on discovery.
            every { prefs.mixStackSeeded } returns flowOf(true)
            every { prefs.pinNewPlaylists } returns flowOf(false)
            coEvery { prefs.markMixStackSeeded() } returns Unit
            every { prefs.spotifySyncMode } answers { flowOf(mode) }
            every { prefs.youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
            coEvery { spotify.getLikedSongs(any(), any()) } returns SyncResult.Empty("none")
            coEvery { spotify.getUserPlaylists(any(), any(), any()) } answers {
                val listed = SpotifyPlaylistItem(
                    id = "pl1", name = "Road Trip", owner = SpotifyOwner(id = "me"),
                    tracks = SpotifyTracksRef(total = 0), revisionId = revision,
                )
                SpotifyLibraryPage(listOf(listed), folderUris = emptyList(), rawItemCount = 1)
            }
            coEvery { spotify.getPlaylistTracks(any()) } answers {
                SyncResult.Success(
                    SpotifyPlaylistTracks(remoteTitles.map(::remoteTrack), countVerified = true, revisionId = revision),
                )
            }
        }

        fun close() = db.close()
    }

    private fun remoteTrack(title: String) = SpotifyTrackItem(
        track = SpotifyTrackObject(
            id = title, name = title,
            artists = listOf(SpotifyArtist(id = "artist", name = "Artist")),
            uri = "spotify:track:$title",
        ),
    )

    /** The playlist's songs A and B, synced; [withCopyOfA] adds a downloaded copy of A from Search. */
    private suspend fun Library.seed(withCopyOfA: Boolean) {
        db.playlistDao().insert(
            PlaylistEntity(name = "Road Trip", source = MusicSource.SPOTIFY, sourceId = "pl1", type = PlaylistType.CUSTOM, syncEnabled = true),
        )
        for (title in listOf("A", "B")) {
            db.trackDao().insert(
                TrackEntity(
                    title = title, artist = "Artist", canonicalTitle = title.lowercase(), canonicalArtist = "artist",
                    spotifyUri = "spotify:track:$title",
                ),
            )
        }
        if (withCopyOfA) {
            db.trackDao().insert(
                TrackEntity(
                    title = "A", artist = "Artist", canonicalTitle = "a", canonicalArtist = "artist",
                    source = MusicSource.YOUTUBE, youtubeId = "yt-a", isDownloaded = true, filePath = "/music/a.flac",
                ),
            )
        }
    }

    private suspend fun Library.trackId(title: String, source: MusicSource) =
        db.trackDao().getAllForIntegrityScan().single { it.title == title && it.source == source }.id

    /**
     * Builds a library: [seed], one full sync (which stamps the playlist), then
     * [perturb]; then the sync under test, forced to read in full when
     * [forceRead], with [midSync] between its fetch and its diff and
     * [fetchDao] standing in for the fetch's PlaylistDao. Returns the tables.
     */
    private fun outcome(
        forceRead: Boolean,
        seed: suspend Library.() -> Unit = { seed(withCopyOfA = false) },
        perturb: suspend Library.() -> Unit = {},
        midSync: suspend Library.() -> Unit = {},
        fetchDao: (Library) -> PlaylistDao = { it.db.playlistDao() },
        check: suspend Library.() -> Unit = {},
    ): List<String> = runBlocking {
        val lib = Library()
        try {
            lib.seed()
            lib.runDiff(lib.runFetch(lib.db.playlistDao()))
            assertTrue("the first full sync stamps the playlist", lib.db.playlistDao().getSnapshotId(lib.playlistId()) != null)
            lib.perturb()
            if (forceRead) lib.db.playlistDao().forgetSpotifyMirrorStamps()
            val output = lib.runFetch(fetchDao(lib))
            lib.midSync()
            lib.runDiff(output)
            lib.check()
            lib.tables()
        } finally {
            lib.close()
        }
    }

    private suspend fun Library.playlistId() = db.playlistDao().findBySourceId("pl1")!!.id

    private fun Library.tables(): List<String> {
        val sqlite = db.openHelper.readableDatabase
        fun rows(sql: String) = sqlite.query(sql).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString(",") { c.getString(it) ?: "null" }) }
        }
        return rows("SELECT 'pt', playlist_id, track_id, position, removed_at IS NOT NULL, locally_added FROM playlist_tracks ORDER BY playlist_id, track_id") +
            rows("SELECT 't', id, title, spotify_uri, youtube_id, is_downloaded, file_path, match_dismissed, album_art_url FROM tracks ORDER BY id") +
            rows("SELECT 'dq', track_id, status FROM download_queue ORDER BY track_id, status")
    }

    private fun assertParity(
        seed: suspend Library.() -> Unit = { seed(withCopyOfA = false) },
        perturb: suspend Library.() -> Unit = {},
        midSync: suspend Library.() -> Unit = {},
        fetchDao: (Library) -> PlaylistDao = { it.db.playlistDao() },
        check: suspend Library.() -> Unit = {},
    ) {
        val skipping = outcome(forceRead = false, seed, perturb, midSync, fetchDao, check)
        val fullRead = outcome(forceRead = true, seed, perturb, midSync, fetchDao)
        assertEquals("a sync free to skip must leave what a full read leaves", fullRead, skipping)
    }

    // ── Cases ────────────────────────────────────────────────────────────

    @Test fun `with nothing changed the playlist is skipped, and that is a full read's result`() =
        assertParity(check = { coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") } })

    @Test fun `D1 a download that failed after the stamp is closed as a full sync closes it`() = assertParity(
        seed = {
            seed(withCopyOfA = true)
            // A was still waiting when the copy got linked, so nothing was closed then.
            db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId("A", MusicSource.SPOTIFY), searchQuery = "Artist - A"))
        },
        perturb = {
            val row = db.downloadQueueDao().getByTrackId(trackId("A", MusicSource.SPOTIFY))!!
            db.downloadQueueDao().updateStatus(id = row.id, status = DownloadStatus.FAILED)
        },
        check = {
            val row = db.downloadQueueDao().getByTrackId(trackId("A", MusicSource.SPOTIFY))!!
            assertEquals(DownloadStatus.COMPLETED, row.status)
        },
    )

    @Test fun `D2 a dismissed match unlinks the copy as a full sync does`() = assertParity(
        seed = { seed(withCopyOfA = true) },
        perturb = { db.trackDao().dismissMatch(trackId("A", MusicSource.SPOTIFY)) },
    )

    @Test fun `D3 a copy retitled to another version is unlinked as a full sync does`() = assertParity(
        seed = { seed(withCopyOfA = true) },
        perturb = {
            val copy = trackId("A", MusicSource.YOUTUBE)
            db.openHelper.writableDatabase.execSQL("UPDATE tracks SET title = 'A (Live)' WHERE id = $copy")
        },
    )

    @Test fun `D3 a copy's newly resolved Spotify id leaves what a full sync leaves`() = assertParity(
        seed = { seed(withCopyOfA = true) },
        perturb = { db.trackDao().updateSpotifyUri(trackId("A", MusicSource.YOUTUBE), "spotify:track:A-remaster") },
    )

    @Test fun `D4 a mode switched between fetch and diff can't split one sync across two modes`() = assertParity(
        seed = {
            seed(withCopyOfA = false)
            // Accumulate: X was on the playlist once and stays after it left.
            mode = SyncMode.ACCUMULATE
            remoteTitles = listOf("A", "B", "X")
            revision = "AAAAAOlderRevisionWithXXXXXXXXXX"
            runDiff(runFetch(db.playlistDao()))
            remoteTitles = listOf("A", "B")
            revision = "AAAAAMv6SY4RNBP4lRibleC3UcWweCUH"
        },
        midSync = { mode = SyncMode.REFRESH },
    )

    @Test fun `D5 a fingerprint that fails reads the playlist in full, inventory intact`() = assertParity(
        fetchDao = { lib ->
            spyk(lib.db.playlistDao()).also { coEvery { it.mirrorDigest(any()) } throws IllegalStateException("boom") }
        },
        check = {
            coVerify(exactly = 2) { spotify.getPlaylistTracks("pl1") }
            assertTrue(
                "the run's inventory stays complete",
                db.syncHistoryDao().getLatest()!!.diagnostics!!.contains("\"getUserPlaylists\",\"status\":\"SUCCESS\""),
            )
        },
    )

    // ── Runners ──────────────────────────────────────────────────────────

    private suspend fun Library.runFetch(playlistDao: PlaylistDao): Data {
        val tokenManager = mockk<TokenManager> {
            coEvery { isAuthenticated(AuthService.SPOTIFY) } returns true
            coEvery { isAuthenticated(AuthService.YOUTUBE_MUSIC) } returns false
        }
        val probe = mockk<SpotifyAuthHealthProbe> { coEvery { isExpired() } returns false }
        val streaming = mockk<StreamingPreference> { coEvery { current() } returns false }
        val result = TestListenableWorkerBuilder<PlaylistFetchWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    PlaylistFetchWorker(
                        appContext, workerParameters,
                        tokenManager = tokenManager,
                        spotifyApiClient = spotify,
                        ytMusicApiClient = mockk(relaxed = true),
                        innerTubeClient = mockk(relaxed = true),
                        playlistDao = playlistDao,
                        syncHistoryDao = db.syncHistoryDao(),
                        remoteSnapshotDao = db.remoteSnapshotDao(),
                        syncStateManager = mockk(relaxed = true),
                        syncLog = com.stash.core.data.sync.SyncLog(),
                        syncNotificationManager = mockk(relaxed = true),
                        syncPreferencesManager = prefs,
                        spotifyAuthHealthProbe = probe,
                        youtubeAuthHealthProbe = mockk(relaxed = true),
                        streamingPreference = streaming,
                        syncUndoDao = db.syncUndoDao(),
                    )
            })
            .build()
            .doWork()
        assertTrue("fetch must succeed: $result", result is ListenableWorker.Result.Success)
        return (result as ListenableWorker.Result.Success).outputData
    }

    /** Runs the diff on the fetch's output, as the sync chain hands it on. */
    private suspend fun Library.runDiff(fetchOutput: Data) {
        val streaming = mockk<StreamingPreference> { coEvery { current() } returns false }
        val result = TestListenableWorkerBuilder<DiffWorker>(context)
            .setInputData(Data.Builder().putAll(fetchOutput).build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    DiffWorker(
                        appContext, workerParameters,
                        database = db,
                        remoteSnapshotDao = db.remoteSnapshotDao(),
                        trackDao = db.trackDao(),
                        playlistDao = db.playlistDao(),
                        downloadQueueDao = db.downloadQueueDao(),
                        syncHistoryDao = db.syncHistoryDao(),
                        trackMatcher = TrackMatcher(),
                        syncStateManager = mockk(relaxed = true),
                        musicRepository = mockk<MusicRepository>(relaxed = true),
                        syncPreferencesManager = prefs,
                        blocklistGuard = mockk<BlocklistGuard> {
                            coEvery { isBlocked(any(), any(), any(), any()) } returns false
                        },
                        streamingPreference = streaming,
                        syncUndoDao = db.syncUndoDao(),
                        syncLog = com.stash.core.data.sync.SyncLog(),
                        canonicalRecompute = mockk(relaxed = true),
                    )
            })
            .build()
            .doWork()
        assertTrue("diff must succeed: $result", result is ListenableWorker.Result.Success)
    }
}
