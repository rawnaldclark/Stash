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
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncLog
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.data.sync.unreadPlaylistsIn
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
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #747: a Spotify playlist with no songs "failed" on every sync, so the library
 * step read "inventory incomplete", REFRESH never hid a playlist deleted on
 * Spotify, a playlist emptied there never emptied here, and a real failure looked
 * the same and named nothing. Driven through [PlaylistFetchWorker.doWork] (and
 * [DiffWorker], as the sync chain runs them) on a real in-memory database.
 *
 * The empty-playlist cases run the real client's song walk over a faked HTTP
 * answer, so they exercise the code that was wrong; the rest fake the client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistFetchWorkerUnreadPlaylistTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val spotify = mockk<SpotifyApiClient>()

    /** What the fetch step reads Spotify through: [spotify], or [useRealWalk]'s real client. */
    private var spotifyClient: SpotifyApiClient = spotify
    private val syncLog = SyncLog()
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val streamingPreference = mockk<StreamingPreference>()
    private val tokenManager = mockk<TokenManager> {
        coEvery { getSoundCloudToken() } returns "secret"
        coEvery { isAuthenticated(AuthService.SPOTIFY) } returns true
        coEvery { isAuthenticated(AuthService.YOUTUBE_MUSIC) } returns false
        // What the real client's GraphQL calls ask for.
        coEvery { getSpotifyAccessToken() } returns "access-token"
        coEvery { getSpotifyClientId() } returns "client-id"
        coEvery { forceRefreshSpotifyAccessToken() } returns null
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
        listing(listed("pl1"))
    }

    @After fun tearDown() = db.close()

    // ── An empty playlist is read fine ───────────────────────────────────

    @Test fun `an empty playlist is read fine, and Refresh still hides one deleted on Spotify`() = runBlocking {
        useRealWalk(fetchPlaylist = emptyPage())
        val gone = db.playlistDao().insert(
            PlaylistEntity(name = "Deleted on Spotify", source = MusicSource.SPOTIFY, sourceId = "gone", type = PlaylistType.CUSTOM),
        )

        val syncId = runFetch()

        val step = userPlaylistsStep(syncId)
        assertEquals(StepStatus.SUCCESS, step.status)
        assertNull(step.errorMessage)
        assertTrue(step.unreadPlaylists.isEmpty())
        assertEquals("the empty playlist counts as read", 1, step.itemCount)
        val snapshot = snapshotOf("pl1", syncId)
        assertFalse(snapshot.partial)
        assertEquals("Spotify's count verified it: the diff may stamp it", revision, snapshot.snapshotId)
        assertFalse("a whole inventory lets Refresh hide it", db.playlistDao().getById(gone)!!.isActive)
        assertTrue(couldNotReadLines().isEmpty())
    }

    @Test fun `a playlist emptied on Spotify empties here under Refresh`() = runBlocking {
        val playlistId = seedPlaylist()
        useRealWalk(fetchPlaylist = emptyPage())

        val syncId = runFetch()
        runDiff(syncId)

        assertTrue(activeMemberships(playlistId).isEmpty())
        assertEquals(0, db.playlistDao().getById(playlistId)!!.trackCount)
        assertNotNull(
            "mirrored completely, so the next sync can skip it",
            SpotifyMirrorStamp.decode(db.playlistDao().getSnapshotId(playlistId)),
        )
    }

    @Test fun `in Accumulate, a playlist emptied on Spotify keeps its songs`() = runBlocking {
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val playlistId = seedPlaylist()
        val before = activeMemberships(playlistId)
        useRealWalk(fetchPlaylist = emptyPage())

        val syncId = runFetch()
        runDiff(syncId)

        assertEquals(before, activeMemberships(playlistId))
    }

    // ── A playlist that can't be read is named, and kept ─────────────────

    @Test fun `a playlist that can't be read is named in the step and the live log, and the rest still sync`() = runBlocking {
        listing(listed("pl1"), listed("pl2"))
        coEvery { spotify.getPlaylistTracks("pl2") } returns
            SyncResult.Error("getPlaylistTracks: both Web API and GraphQL failed for pl2")
        val gone = db.playlistDao().insert(
            PlaylistEntity(name = "Deleted on Spotify", source = MusicSource.SPOTIFY, sourceId = "gone", type = PlaylistType.CUSTOM),
        )

        val syncId = runFetch()

        val step = userPlaylistsStep(syncId)
        assertEquals(StepStatus.ERROR, step.status)
        assertEquals("couldn't read 1 playlist: Playlist pl2 (kept as it was)", step.errorMessage)
        assertEquals(listOf("Playlist pl2"), step.unreadPlaylists)
        assertEquals("the one that was read still counts", 1, step.itemCount)
        assertEquals(listOf("Playlist pl2 — couldn't read its songs (kept as it was)"), couldNotReadLines())
        assertEquals(2, db.remoteSnapshotDao().getTrackSnapshotsByPlaylistId(snapshotOf("pl1", syncId).id).size)
        assertTrue(snapshotOf("pl2", syncId).partial)
        assertTrue("one unread playlist keeps Refresh from hiding anything", db.playlistDao().getById(gone)!!.isActive)
        assertEquals(
            "what the History row reads back",
            listOf("Playlist pl2"),
            unreadPlaylistsIn(db.syncHistoryDao().getById(syncId)!!.diagnostics),
        )
    }

    @Test fun `a read that throws is named like one that errors`() = runBlocking {
        coEvery { spotify.getPlaylistTracks("pl1") } throws IllegalStateException("socket closed")

        val syncId = runFetch()

        assertEquals(listOf("Playlist pl1"), userPlaylistsStep(syncId).unreadPlaylists)
        assertEquals(listOf("Playlist pl1 — couldn't read its songs (kept as it was)"), couldNotReadLines())
    }

    @Test fun `a playlist that can't be read keeps its songs through Refresh, and is read again next sync`() = runBlocking {
        val playlistId = seedPlaylist()
        val before = activeMemberships(playlistId)
        coEvery { spotify.getPlaylistTracks("pl1") } returns SyncResult.Error("boom")

        val syncId = runFetch()
        runDiff(syncId)

        assertEquals(before, activeMemberships(playlistId))
        assertEquals(2, db.playlistDao().getById(playlistId)!!.trackCount)
        assertNull("never stamped, so never skipped", db.playlistDao().getSnapshotId(playlistId))
        runFetch()
        coVerify(exactly = 2) { spotify.getPlaylistTracks("pl1") }
    }

    @Test fun `a healthy run names nothing`() = runBlocking {
        val syncId = runFetch()

        val step = userPlaylistsStep(syncId)
        assertEquals(StepStatus.SUCCESS, step.status)
        assertNull(step.errorMessage)
        assertTrue(step.unreadPlaylists.isEmpty())
        assertTrue(couldNotReadLines().isEmpty())
        assertTrue(unreadPlaylistsIn(db.syncHistoryDao().getById(syncId)!!.diagnostics).isEmpty())
    }

    // ── The step message ─────────────────────────────────────────────────

    @Test fun `the step message names up to five playlists`() {
        assertEquals(
            "couldn't read 7 playlists: A, B, C, D, E and 2 more (kept as they were)",
            unreadPlaylistsMessage(listOf("A", "B", "C", "D", "E", "F", "G")),
        )
        assertEquals(
            "couldn't read 2 playlists: Road trip, Gym (kept as they were)",
            unreadPlaylistsMessage(listOf("Road trip", "Gym")),
        )
    }

    @Test fun `a listing walk that failed is kept apart from the playlists not read`() {
        assertEquals(
            "inventory incomplete — a page/folder fetch failed mid-walk; couldn't read 1 playlist: X (kept as it was)",
            spotifyInventoryProblem(listingComplete = false, unreadPlaylists = listOf("X")),
        )
        assertEquals(
            "inventory incomplete — a page/folder fetch failed mid-walk",
            spotifyInventoryProblem(listingComplete = false, unreadPlaylists = emptyList()),
        )
        assertNull(spotifyInventoryProblem(listingComplete = true, unreadPlaylists = emptyList()))
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun listed(id: String) = SpotifyPlaylistItem(
        id = id,
        name = "Playlist $id",
        owner = SpotifyOwner(id = "testuser"),
        tracks = SpotifyTracksRef(total = 0),
        revisionId = revision,
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

    /** Spotify's `fetchPlaylist` answer for a playlist with no songs: an empty list, a count of 0. */
    private fun emptyPage() =
        """{"data":{"playlistV2":{"revisionId":"$revision","content":{"totalCount":0,"items":[]}}}}"""

    /** "pl1" as an earlier sync left it: two songs, switched on, never stamped. */
    private suspend fun seedPlaylist(): Long {
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Playlist pl1", source = MusicSource.SPOTIFY, sourceId = "pl1",
                type = PlaylistType.CUSTOM, syncEnabled = true,
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

    /** The live log's lines about a playlist whose songs couldn't be read. */
    private fun couldNotReadLines() = syncLog.lines.value
        .filter { it.level == SyncLog.Level.WARN && "couldn't read" in it.text }
        .map { it.text }

    // ── The real song walk ───────────────────────────────────────────────

    /** What Spotify's `fetchPlaylist` answers, for every playlist, in a [useRealWalk] test. */
    private var fetchPlaylistBody = ""

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
            .body(fetchPlaylistBody.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken(any()) } returns "client-token"
        // No client_credentials token: the Web API prong is skipped and the GraphQL walk reads.
        coEvery { getClientCredentialsToken() } returns null
        every { scrapePersistedQueryHash(any(), any()) } returns null
    }

    /**
     * Reads every playlist's songs through the real [SpotifyApiClient], Spotify
     * answering [fetchPlaylist]; the library listing and Liked Songs stay faked.
     */
    private fun useRealWalk(fetchPlaylist: String) {
        fetchPlaylistBody = fetchPlaylist
        spotifyClient = spyk(SpotifyApiClient(http, tokenManager, authManager)).also { real ->
            coEvery { real.getUserPlaylists(any(), any(), any()) } coAnswers {
                spotify.getUserPlaylists(arg(0), arg(1), arg(2))
            }
            coEvery { real.getLikedSongs(any(), any()) } returns SyncResult.Empty("no liked songs")
        }
    }

    // ── Runners ──────────────────────────────────────────────────────────

    /** Runs the fetch step; returns its sync id. */
    private suspend fun runFetch(): Long {
        val result = TestListenableWorkerBuilder<PlaylistFetchWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = PlaylistFetchWorker(
                    appContext, workerParameters,
                    tokenManager = tokenManager,
                    spotifyApiClient = spotifyClient,
                    ytMusicApiClient = mockk(relaxed = true),
                    innerTubeClient = mockk(relaxed = true),
                    playlistDao = db.playlistDao(),
                    syncHistoryDao = db.syncHistoryDao(),
                    remoteSnapshotDao = db.remoteSnapshotDao(),
                    syncStateManager = mockk(relaxed = true),
                    syncLog = syncLog,
                    syncNotificationManager = mockk(relaxed = true),
                    syncPreferencesManager = syncPreferencesManager,
                    spotifyAuthHealthProbe = spotifyProbe,
                    youtubeAuthHealthProbe = mockk(relaxed = true),
                    streamingPreference = streamingPreference,
                    syncUndoDao = db.syncUndoDao(),
                )
            })
            .build()
            .doWork()
        assertTrue("fetch must succeed: $result", result is ListenableWorker.Result.Success)
        return (result as ListenableWorker.Result.Success).outputData.getLong(PlaylistFetchWorker.KEY_SYNC_ID, -1L)
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
                    syncLog = SyncLog(),
                    canonicalRecompute = mockk(relaxed = true),
                )
            })
            .build()
            .doWork()
}
