package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.StepStatus
import com.stash.core.model.SyncMode
import com.stash.core.model.SyncResult
import com.stash.core.model.SyncStepResult
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 * The fetch step's half of skipping unchanged Spotify playlists (see
 * [SpotifyMirrorStamp]), driven through [PlaylistFetchWorker.doWork] on a real
 * in-memory database with Spotify faked: a playlist whose stamp still vouches
 * for it is not read, every other one is, and a skipped playlist stays in the
 * run's inventory. The end-to-end cases then run [DiffWorker] on the same
 * database, as the sync chain does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistFetchWorkerRevisionSkipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val spotify = mockk<SpotifyApiClient>()
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val streamingPreference = mockk<StreamingPreference>()
    private val tokenManager = mockk<TokenManager> {
        coEvery { isAuthenticated(AuthService.SPOTIFY) } returns true
        coEvery { isAuthenticated(AuthService.YOUTUBE_MUSIC) } returns false
    }
    private val spotifyProbe = mockk<SpotifyAuthHealthProbe> {
        coEvery { isExpired() } returns false
    }

    private val revision = "AAAAAMv6SY4RNBP4lRibleC3UcWweCUH"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Only the library walk is under test: no home-feed mixes, no Liked Songs.
        every { syncPreferencesManager.discoverAutoMixes } returns flowOf(false)
        // The sync tab overhaul's Stack seeding has already run.
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(true)
        coEvery { syncPreferencesManager.markMixStackSeeded() } returns Unit
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(false)
        coEvery { spotify.getLikedSongs(any(), any()) } returns SyncResult.Empty("no liked songs")
        coEvery { spotify.getPlaylistTracks(any()) } returns SyncResult.Success(
            SpotifyPlaylistTracks(tracks = listOf(track("A"), track("B")), countVerified = true, revisionId = revision),
        )
        coEvery { streamingPreference.current() } returns false
        listing(listed("pl1", revision))
    }

    @After fun tearDown() = db.close()

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun listed(id: String, revisionId: String?) = SpotifyPlaylistItem(
        id = id,
        name = "Playlist $id",
        owner = SpotifyOwner(id = "testuser"),
        tracks = SpotifyTracksRef(total = 0),
        revisionId = revisionId,
    )

    private fun listing(vararg playlists: SpotifyPlaylistItem) {
        coEvery { spotify.getUserPlaylists(any(), any(), any()) } returns
            SpotifyLibraryPage(playlists.toList(), folderUris = emptyList(), rawItemCount = playlists.size)
    }

    private fun track(title: String) = SpotifyTrackItem(
        track = SpotifyTrackObject(
            id = title, name = title,
            artists = listOf(SpotifyArtist(id = "artist", name = "Artist")),
            uri = "spotify:track:$title",
        ),
    )

    /**
     * "pl1" as the last full sync left it: two songs, and a stamp vouching for
     * them at [stampRevision] in [stampMode], written [ageMs] ago.
     */
    private suspend fun seedMirroredPlaylist(
        stampRevision: String = revision,
        stampMode: SyncMode = SyncMode.REFRESH,
        ageMs: Long = 60 * 60 * 1000,
        syncEnabled: Boolean = true,
    ): Long {
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Playlist pl1", source = MusicSource.SPOTIFY, sourceId = "pl1",
                type = PlaylistType.CUSTOM, syncEnabled = syncEnabled,
            ),
        )
        listOf("A", "B").forEachIndexed { i, title ->
            val trackId = db.trackDao().insert(
                TrackEntity(
                    title = title, artist = "Artist",
                    canonicalTitle = title.lowercase(), canonicalArtist = "artist",
                    spotifyUri = "spotify:track:$title",
                ),
            )
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = i))
        }
        db.playlistDao().updateTrackCount(playlistId, 2)
        val stamp = SpotifyMirrorStamp(
            revision = stampRevision,
            syncMode = stampMode,
            recordedAtMs = System.currentTimeMillis() - ageMs,
            digest = db.playlistDao().mirrorDigest(playlistId)!!,
            appVersion = appVersionCode(context),
        ).encode()
        db.playlistDao().updateSnapshotId(playlistId, stamp)
        return playlistId
    }

    private suspend fun snapshotOf(sourceId: String, syncId: Long) =
        db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).single { it.sourcePlaylistId == sourceId }

    private suspend fun userPlaylistsStep(syncId: Long): SyncStepResult =
        Json.decodeFromString<List<SyncStepResult>>(db.syncHistoryDao().getById(syncId)!!.diagnostics!!)
            .single { it.step == "getUserPlaylists" }

    private suspend fun activeMemberships(playlistId: Long) =
        db.playlistDao().getCrossRefsForPlaylist(playlistId)
            .filter { it.removedAt == null }
            .map { it.trackId to it.position }
            .sortedBy { it.second }

    // ── Skip ─────────────────────────────────────────────────────────────

    @Test fun `an unchanged playlist is not read, and stays in the run as checked`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        val stamp = db.playlistDao().getSnapshotId(playlistId)

        val syncId = runFetch()

        coVerify(exactly = 0) { spotify.getPlaylistTracks(any()) }
        val snapshot = snapshotOf("pl1", syncId)
        assertEquals("the diff matches this against the playlist's own stamp", stamp, snapshot.snapshotId)
        assertTrue("never a mirror source", snapshot.partial)
        assertTrue(db.remoteSnapshotDao().getTrackSnapshotsBySyncId(syncId).isEmpty())
        val step = userPlaylistsStep(syncId)
        assertEquals("a skipped playlist is still a synced one", 1, step.itemCount)
        assertEquals(StepStatus.SUCCESS, step.status)
        assertEquals("shown in a shared diagnostics bundle", "1 unchanged since the last sync, not re-read", step.note)
        assertNull("no error on a clean run", step.errorMessage)
    }

    @Test fun `an unchanged playlist comes out of the whole sync exactly as it went in`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        val before = activeMemberships(playlistId)
        val stamp = db.playlistDao().getSnapshotId(playlistId)

        val syncId = runFetch()
        val diff = runDiff(syncId)

        assertTrue(diff is ListenableWorker.Result.Success)
        assertEquals(before, activeMemberships(playlistId))
        assertEquals(2, db.playlistDao().getById(playlistId)!!.trackCount)
        assertEquals(stamp, db.playlistDao().getSnapshotId(playlistId))
    }

    @Test fun `a skipped playlist is never hidden as missing from the account`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        val gone = db.playlistDao().insert(
            PlaylistEntity(name = "Unsaved", source = MusicSource.SPOTIFY, sourceId = "gone", type = PlaylistType.CUSTOM),
        )

        runFetch()

        // REFRESH with a complete inventory: the missing-playlist sweep ran...
        assertFalse(db.playlistDao().getById(gone)!!.isActive)
        // ...and the listed-but-skipped playlist counted as present.
        assertTrue(db.playlistDao().getById(playlistId)!!.isActive)
    }

    // ── Read ─────────────────────────────────────────────────────────────

    @Test fun `a playlist changed on Spotify is read, its snapshot carrying the listing's revision`() = runBlocking {
        seedMirroredPlaylist(stampRevision = "AAAAAOlderRevisionBeforeTheEditXX")

        val syncId = runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
        val snapshot = snapshotOf("pl1", syncId)
        assertEquals("read before the tracks, so never newer than them", revision, snapshot.snapshotId)
        assertFalse(snapshot.partial)
        assertEquals(2, db.remoteSnapshotDao().getTrackSnapshotsBySyncId(syncId).size)
        assertNull("nothing skipped, nothing noted", userPlaylistsStep(syncId).note)
    }

    @Test fun `a playlist the listing gives no revision for is read`() = runBlocking {
        seedMirroredPlaylist()
        listing(listed("pl1", revisionId = null))

        val syncId = runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
        assertNull("nothing to stamp it with", snapshotOf("pl1", syncId).snapshotId)
    }

    @Test fun `a playlist new to this phone is read`() = runBlocking {
        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `a playlist whose last full sync is a week old is read however unchanged`() = runBlocking {
        seedMirroredPlaylist(ageMs = SpotifyMirrorStamp.MAX_AGE_MS + 60_000)

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `a playlist changed here since its last sync is read, so the sync can act on it`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        // The playlist page's remove; any change to its rows does the same.
        val trackId = db.playlistDao().getCrossRefsForPlaylist(playlistId).first().trackId
        db.playlistDao().softDeleteTrackFromPlaylist(playlistId, trackId)

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `an app update reads every playlist once`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        val stamp = SpotifyMirrorStamp.decode(db.playlistDao().getSnapshotId(playlistId))!!
        db.playlistDao().updateSnapshotId(playlistId, stamp.copy(appVersion = stamp.appVersion - 1).encode())

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `the fetch hands its Spotify mode to the diff`() = runBlocking {
        val output = runFetchOutput()

        assertEquals("REFRESH", output.getString(PlaylistFetchWorker.KEY_SPOTIFY_SYNC_MODE))
    }

    @Test fun `a switch between Accumulate and Refresh reads every playlist again`() = runBlocking {
        seedMirroredPlaylist(stampMode = SyncMode.ACCUMULATE)

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `a forgotten stamp reads the playlist`() = runBlocking {
        seedMirroredPlaylist()
        // What a block or an unblock does (see PlaylistDao.forgetSpotifyMirrorStamps).
        db.playlistDao().forgetSpotifyMirrorStamps()

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `a fetch Spotify's count didn't verify carries no revision, so it is read again next time`() = runBlocking {
        coEvery { spotify.getPlaylistTracks(any()) } returns SyncResult.Success(
            SpotifyPlaylistTracks(tracks = listOf(track("A"), track("B")), countVerified = false, revisionId = revision),
        )

        val syncId = runFetch()

        assertNull(snapshotOf("pl1", syncId).snapshotId)
    }

    @Test fun `a playlist that changed between the listing and the fetch is mirrored but not stamped`() = runBlocking {
        // The walk's first page reports a newer revision than the listing read just before.
        coEvery { spotify.getPlaylistTracks(any()) } returns SyncResult.Success(
            SpotifyPlaylistTracks(
                tracks = listOf(track("A"), track("B")), countVerified = true,
                revisionId = "AAAAANewerRevisionMidSyncXXXXXXX",
            ),
        )

        val syncId = runFetch()

        val snapshot = snapshotOf("pl1", syncId)
        assertFalse("still mirrored in full", snapshot.partial)
        assertNull("not stamped, so the next sync reads it again", snapshot.snapshotId)
    }

    @Test fun `a fetch whose first page carried no revision is not stamped`() = runBlocking {
        coEvery { spotify.getPlaylistTracks(any()) } returns SyncResult.Success(
            SpotifyPlaylistTracks(tracks = listOf(track("A"), track("B")), countVerified = true, revisionId = null),
        )

        val syncId = runFetch()

        assertNull(snapshotOf("pl1", syncId).snapshotId)
    }

    @Test fun `a Spotify-generated playlist is read every time, even carrying a stamp`() = runBlocking {
        val ownedId = "37i9dQZEVXcDiscoverWeekly"
        listing(listed(ownedId, revision))
        val pid = db.playlistDao().insert(
            PlaylistEntity(name = "Discover Weekly", source = MusicSource.SPOTIFY, sourceId = ownedId, type = PlaylistType.CUSTOM),
        )
        db.playlistDao().updateSnapshotId(
            pid,
            SpotifyMirrorStamp(revision, SyncMode.REFRESH, System.currentTimeMillis(), db.playlistDao().mirrorDigest(pid)!!, appVersionCode(context)).encode(),
        )

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks(ownedId) }
    }

    @Test fun `a failed fetch carries no revision`() = runBlocking {
        coEvery { spotify.getPlaylistTracks(any()) } returns SyncResult.Error("boom")

        val syncId = runFetch()

        val snapshot = snapshotOf("pl1", syncId)
        assertTrue(snapshot.partial)
        assertNull(snapshot.snapshotId)
    }

    // ── A song's copies changed: read in full, and the diff acts as always ──

    /** Song "A" failed to download; the user then downloaded the same song from Search. */
    private suspend fun downloadCopyOfA(playlistId: Long): Pair<Long, Long> {
        val stubA = db.playlistDao().getCrossRefsForPlaylist(playlistId).minBy { it.position }.trackId
        val failedId = db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = stubA, syncId = null, status = DownloadStatus.FAILED, searchQuery = "Artist - A"),
        )
        val copy = db.trackDao().insert(
            TrackEntity(
                title = "A", artist = "Artist", canonicalTitle = "a", canonicalArtist = "artist",
                source = MusicSource.YOUTUBE, youtubeId = "yt-a", isDownloaded = true, filePath = "/music/a.flac",
            ),
        )
        return copy to failedId
    }

    @Test fun `a song downloaded elsewhere makes the next sync read the playlist and link it in`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        val (copy, failedId) = downloadCopyOfA(playlistId)

        val syncId = runFetch()
        runDiff(syncId)

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
        // Exactly what a full sync always did: the copy joins at the stub's position
        // and the stub's failed download is closed.
        assertEquals(0, db.playlistDao().getCrossRefsForPlaylist(playlistId).single { it.trackId == copy }.position)
        assertEquals(DownloadStatus.COMPLETED, db.downloadQueueDao().getStatusById(failedId))
        // The new stamp vouches for the new state: the sync after skips again.
        runFetch()
        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `in Accumulate too, a new copy of a song makes the next sync read the playlist`() = runBlocking {
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val playlistId = seedMirroredPlaylist(stampMode = SyncMode.ACCUMULATE)
        downloadCopyOfA(playlistId)

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `a library change to other songs still lets the playlist be skipped`() = runBlocking {
        seedMirroredPlaylist()
        db.trackDao().insert(
            TrackEntity(
                title = "Elsewhere", artist = "Someone", canonicalTitle = "elsewhere", canonicalArtist = "someone",
                isDownloaded = true, filePath = "/music/elsewhere.flac",
            ),
        )

        runFetch()

        coVerify(exactly = 0) { spotify.getPlaylistTracks(any()) }
    }

    // ── End to end: read, stamp, then skip ───────────────────────────────

    @Test fun `a playlist read and mirrored completely is skipped by the next sync`() = runBlocking {
        // Stream-only: a playlist new to the phone starts switched off, and only
        // there does the diff mirror (and so stamp) a switched-off playlist.
        coEvery { streamingPreference.current() } returns true
        val first = runFetch()
        runDiff(first)
        val playlistId = db.playlistDao().findBySourceId("pl1")!!.id
        assertTrue("stamped by the diff", SpotifyMirrorStamp.decode(db.playlistDao().getSnapshotId(playlistId)) != null)

        runFetch()

        coVerify(exactly = 1) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `switching on a skipped playlist's download still queues its songs`() = runBlocking {
        // Download mode, switched off: skipped by the fetch, and the diff never
        // touches a switched-off playlist anyway. The songs it holds are what the
        // sync's download step queues for a switched-on playlist, so they must
        // still be there for the switch to take effect without a re-read.
        val playlistId = seedMirroredPlaylist(syncEnabled = false)
        val syncId = runFetch()
        runDiff(syncId)
        coVerify(exactly = 0) { spotify.getPlaylistTracks(any()) }

        db.playlistDao().setSyncEnabledAndPin(playlistId, enabled = true, now = System.currentTimeMillis())

        val queued = db.downloadQueueDao().getUnqueuedTrackIds(listOf("SPOTIFY", "BOTH"))
        assertEquals(activeMemberships(playlistId).map { it.first }.toSet(), queued.toSet())
    }

    // ── Runners ──────────────────────────────────────────────────────────

    /** Runs the fetch step; returns its sync id. */
    private suspend fun runFetch(): Long =
        runFetchOutput().getLong(PlaylistFetchWorker.KEY_SYNC_ID, -1L)

    /** Runs the fetch step; returns its output data. */
    @Test fun `public SoundCloud profile enters the real fetch worker without Spotify or YouTube auth`() = runBlocking {
        coEvery { tokenManager.isAuthenticated(any()) } returns false
        val session = com.stash.core.auth.soundcloud.SoundCloudSession("7", "Public", connectionId = "current")
        every { tokenManager.soundCloudSession } returns kotlinx.coroutines.flow.flowOf(session)
        val fetcher = mockk<com.stash.core.data.sync.SoundCloudLikesFetcher>()
        coEvery { fetcher.fetch(any(), session) } returns true
        runFetchOutput(fetcher)
        coVerify(exactly = 1) { fetcher.fetch(any(), session) }
    }

    @Test fun `authenticated SoundCloud runs likes playlists and mix fetches from the real worker`() = runBlocking {
        every { syncPreferencesManager.discoverAutoMixes } returns flowOf(true)
        coEvery { tokenManager.isAuthenticated(any()) } returns false
        val session = com.stash.core.auth.soundcloud.SoundCloudSession("7", "Listener", oauthToken = "secret", connectionId = "current")
        every { tokenManager.soundCloudSession } returns kotlinx.coroutines.flow.flowOf(session)
        val likes = mockk<com.stash.core.data.sync.SoundCloudLikesFetcher>()
        val lists = mockk<com.stash.core.data.sync.SoundCloudPlaylistsFetcher>()
        coEvery { likes.fetch(any(), session) } returns true
        coEvery { lists.fetchPlaylists(any(), session) } returns com.stash.core.data.sync.SoundCloudPlaylistFetchResult(published = 1)
        coEvery { lists.fetchMixes(any(), session) } returns com.stash.core.data.sync.SoundCloudPlaylistFetchResult(published = 1)
        runFetchOutput(likes, lists)
        coVerify(exactly = 1) { lists.fetchPlaylists(any(), session) }
        coVerify(exactly = 1) { lists.fetchMixes(any(), session) }
    }

    @Test fun `healthy SoundCloud playlists continue when likes and one playlist fail`() = runBlocking {
        every { syncPreferencesManager.discoverAutoMixes } returns flowOf(true)
        coEvery { tokenManager.isAuthenticated(any()) } returns false
        val session = com.stash.core.auth.soundcloud.SoundCloudSession("7", "Listener", oauthToken = "secret", connectionId = "current")
        every { tokenManager.soundCloudSession } returns kotlinx.coroutines.flow.flowOf(session)
        val likes = mockk<com.stash.core.data.sync.SoundCloudLikesFetcher>()
        val lists = mockk<com.stash.core.data.sync.SoundCloudPlaylistsFetcher>()
        coEvery { likes.fetch(any(), session) } throws java.io.IOException("Unavailable")
        coEvery { lists.fetchPlaylists(any(), session) } returns com.stash.core.data.sync.SoundCloudPlaylistFetchResult(published = 1, failed = 1)
        coEvery { lists.fetchMixes(any(), session) } returns com.stash.core.data.sync.SoundCloudPlaylistFetchResult(skipped = true)
        runFetchOutput(likes, lists) // Success means the good snapshots can still reach DiffWorker.
        Unit
    }

    @Test fun `discovery off skips SoundCloud Home mixes but still fetches owned playlists and likes`() = runBlocking {
        every { syncPreferencesManager.discoverAutoMixes } returns flowOf(false)
        coEvery { tokenManager.isAuthenticated(any()) } returns false
        val session = com.stash.core.auth.soundcloud.SoundCloudSession("7", "Listener", oauthToken = "secret", connectionId = "current")
        every { tokenManager.soundCloudSession } returns kotlinx.coroutines.flow.flowOf(session)
        val likes = mockk<com.stash.core.data.sync.SoundCloudLikesFetcher>()
        val lists = mockk<com.stash.core.data.sync.SoundCloudPlaylistsFetcher>()
        coEvery { likes.fetch(any(), session) } returns true
        coEvery { lists.fetchPlaylists(any(), session) } returns com.stash.core.data.sync.SoundCloudPlaylistFetchResult(published = 1)
        runFetchOutput(likes, lists)
        coVerify(exactly = 0) { lists.fetchMixes(any(), any()) }
        coVerify(exactly = 1) { lists.fetchPlaylists(any(), session) }
    }

    private suspend fun runFetchOutput(soundCloud: com.stash.core.data.sync.SoundCloudLikesFetcher? = null,
        lists: com.stash.core.data.sync.SoundCloudPlaylistsFetcher? = null): androidx.work.Data {
        val result = TestListenableWorkerBuilder<PlaylistFetchWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = PlaylistFetchWorker(
                    appContext, workerParameters,
                    tokenManager = tokenManager,
                    spotifyApiClient = spotify,
                    ytMusicApiClient = mockk(relaxed = true),
                    innerTubeClient = mockk(relaxed = true),
                    playlistDao = db.playlistDao(),
                    syncHistoryDao = db.syncHistoryDao(),
                    remoteSnapshotDao = db.remoteSnapshotDao(),
                    syncStateManager = mockk(relaxed = true),
                    syncLog = com.stash.core.data.sync.SyncLog(),
                    syncNotificationManager = mockk(relaxed = true),
                    syncPreferencesManager = syncPreferencesManager,
                    spotifyAuthHealthProbe = spotifyProbe,
                    youtubeAuthHealthProbe = mockk(relaxed = true),
                    streamingPreference = streamingPreference,
                    syncUndoDao = db.syncUndoDao(),
                    soundCloudLikes = soundCloud,
                    soundCloudPlaylists = lists,
                )
            })
            .build()
            .doWork()
        assertTrue("fetch must succeed: $result", result is ListenableWorker.Result.Success)
        return (result as ListenableWorker.Result.Success).outputData
    }

    private suspend fun runDiff(syncId: Long): ListenableWorker.Result =
        TestListenableWorkerBuilder<DiffWorker>(context)
            .setInputData(
                workDataOf(
                    PlaylistFetchWorker.KEY_SYNC_ID to syncId,
                    PlaylistFetchWorker.KEY_YOUTUBE_INVENTORY_COMPLETE to true,
                ),
            )
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
                    syncStateManager = mockk(relaxed = true),
                    musicRepository = mockk<MusicRepository>(relaxed = true),
                    syncPreferencesManager = syncPreferencesManager,
                    blocklistGuard = mockk<BlocklistGuard> {
                        coEvery { isBlocked(any(), any(), any(), any()) } returns false
                    },
                    streamingPreference = streamingPreference,
                    syncUndoDao = db.syncUndoDao(),
                    syncLog = com.stash.core.data.sync.SyncLog(),
                    canonicalRecompute = mockk(relaxed = true),
                )
            })
            .build()
            .doWork()
}
