package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.spotify.SpotifyAccount
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.SpotifyAccountEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SpotifyExtraAccountRemover
import com.stash.core.data.sync.SpotifyPlaylistIds
import com.stash.core.data.sync.SpotifySavedAlbumsFetcher
import com.stash.core.data.sync.SyncElapsedClock
import com.stash.core.data.sync.SyncLog
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.StepStatus
import com.stash.core.model.SyncMode
import com.stash.core.model.SyncResult
import com.stash.core.model.SyncStepResult
import com.stash.data.spotify.SpotifyAlbumTrack
import com.stash.data.spotify.SpotifyAlbumTracklist
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyApiException
import com.stash.data.spotify.SpotifyLibraryPage
import com.stash.data.spotify.SpotifyLikedSongsPage
import com.stash.data.spotify.SpotifyPlaylistTracks
import com.stash.data.spotify.SpotifySavedAlbum
import com.stash.data.spotify.SpotifySavedAlbumsPage
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
import kotlinx.coroutines.currentCoroutineContext
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
 * Several Spotify accounts through the real sync (plan 2026-11-04, Wave 2): [PlaylistFetchWorker]
 * then [DiffWorker], as the chain runs them, on a real in-memory database. "A" is the main account,
 * "B" ([userB]) an extra one.
 *
 * MockK runs a `coAnswers` block in a coroutine of its own, so a faked [SpotifyApiClient] can't see
 * which account is acting. [ActingTokenManager] can (it is a real suspend implementation): every
 * token read records the acting account, and the fake answers from that account's [Library]. The
 * worker asks for an extra's token right before its pass, so the fake follows each pass. The
 * wiring test at the end proves the acting account reaches the network with the real client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistFetchWorkerSpotifyAccountsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val syncLog = SyncLog()
    private val prefs = mockk<SyncPreferencesManager>()
    private val streamingPreference = mockk<StreamingPreference>()
    private val tokens = ActingTokenManager(mockk(relaxed = true))
    private val spotify = mockk<SpotifyApiClient>()
    private var spotifyClient: SpotifyApiClient = spotify
    private val probe = mockk<SpotifyAuthHealthProbe> { coEvery { isExpired() } returns false }
    private val revision = "AAAAAMv6SY4RNBP4lRibleC3UcWweCUH"

    private val userB = "userb"
    private val userC = "userc"

    /** The worker's clock; a fake read takes as long as a test says ([takesMinutes]). */
    private val clock = object : SyncElapsedClock() {
        var now = 1_000_000L
        override fun nowMs() = now
    }

    /** A sync started in the background on Android 12+: it can't go foreground. */
    private val refusesForeground = ForegroundUpdater { _, _, _ ->
        SettableFuture.create<Void>().apply { setException(IllegalStateException("started in the background")) }
    }

    /** One account's Spotify library, as the fake answers it while that account acts. */
    private class Library {
        val playlists = mutableListOf<String>()
        val liked = mutableListOf<String>()
        val albums = mutableListOf<String>()
        val mixes = mutableListOf<String>()
        var listingFails = false

        /** The listing answers, but says a part of it is missing (a folder too deep, say). */
        var listingPartial = false
    }

    private val libraries = HashMap<String?, Library>()
    private fun library(owner: String?) = libraries.getOrPut(owner) { Library() }
    private fun acting() = library(tokens.actingNow)

    /** Songs per playlist, mix or album id, whoever lists it. */
    private val songs = HashMap<String, List<String>>()

    /** The client's count of 429 answers ([SpotifyApiClient.rateLimitedAnswers]). */
    private var rateLimited = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { prefs.discoverAutoMixes } returns flowOf(false)
        every { prefs.mixStackSeeded } returns flowOf(true)
        coEvery { prefs.markMixStackSeeded() } returns Unit
        every { prefs.spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { prefs.youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
        every { prefs.pinNewPlaylists } returns flowOf(false)
        every { prefs.syncSpotifySavedAlbums } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true

        coEvery { spotify.getUserPlaylists(any(), any(), any()) } answers {
            val lib = acting()
            if (lib.listingFails) {
                SpotifyLibraryPage.EMPTY
            } else {
                SpotifyLibraryPage(
                    lib.playlists.map(::listed), folderUris = emptyList(), rawItemCount = lib.playlists.size,
                    isComplete = !lib.listingPartial,
                )
            }
        }
        coEvery { spotify.getPlaylistTracks(any()) } answers {
            val id = firstArg<String>()
            SyncResult.Success(SpotifyPlaylistTracks(songsOf(id).map(::track), countVerified = true, revisionId = revision))
        }
        coEvery { spotify.getLikedSongs(any(), any()) } answers {
            val lib = acting()
            if (secondArg<Int>() > 0 || lib.liked.isEmpty()) {
                SyncResult.Empty("no more")
            } else {
                SyncResult.Success(SpotifyLikedSongsPage(lib.liked.map(::track), rawItemCount = lib.liked.size, totalCount = lib.liked.size))
            }
        }
        coEvery { spotify.getDailyMixes() } answers {
            SyncResult.Success(acting().mixes.map { SpotifyPlaylistItem(id = it, name = "Mix $it", owner = SpotifyOwner(id = "spotify")) })
        }
        coEvery { spotify.getYearlyMixes() } returns SyncResult.Empty("none")
        every { spotify.rateLimitedAnswers } answers { rateLimited }
        coEvery { spotify.getSavedAlbumsPage(any(), any(), any()) } answers {
            val lib = acting()
            val offset = secondArg<Int>()
            val page = if (offset == 0) lib.albums.map(::savedAlbum) else emptyList()
            SpotifySavedAlbumsPage(
                page, page.map { it.id }, rawItemCount = page.size, totalCount = lib.albums.size, offset = offset,
                sortOrder = SpotifyApiClient.SAVED_ALBUMS_ORDER,
            )
        }
        coEvery { spotify.getAlbumTracks(any()) } answers {
            val titles = songsOf(firstArg())
            SpotifyAlbumTracklist(
                tracks = titles.mapIndexed { i, t ->
                    SpotifyAlbumTrack(
                        id = "Trk$t".padEnd(22, 'x'), name = t, artists = listOf("Artist"), durationMs = 200_000L,
                        explicit = false, discNumber = 1, trackNumber = i + 1,
                    )
                },
                totalCount = titles.size, complete = true, walkedAll = true, pageFailed = false, pageCapHit = false,
            )
        }
    }

    @After fun tearDown() = db.close()

    // ── Claims and ownership ─────────────────────────────────────────────

    @Test fun `a playlist both accounts list gives one snapshot and one row, and ownership settles on the main`() = runBlocking {
        addExtra(userB)
        library(null).playlists += listOf("shared", "a1")
        library(userB).playlists += listOf("shared", "b1")
        // An earlier sync left the shared playlist with B (A hadn't followed it yet).
        db.playlistDao().insert(PlaylistEntity(name = "Playlist shared", source = MusicSource.SPOTIFY, sourceId = "shared", type = PlaylistType.CUSTOM, spotifyAccount = userB))

        val syncId = runFetch()

        val snapshots = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId)
        val shared = snapshots.filter { it.sourcePlaylistId == "shared" }
        assertEquals("one snapshot for the playlist both follow", 1, shared.size)
        assertNull("the main's pass took it", shared.single().spotifyAccount)
        assertEquals(userB, snapshots.single { it.sourcePlaylistId == "b1" }.spotifyAccount)
        coVerify(exactly = 1) { spotify.getPlaylistTracks("shared") }
        assertEquals("1 also on an account read before, read there", step(syncId, "getUserPlaylists", "extra 1").note)

        runDiff(syncId)

        val rows = db.playlistDao().findBySourceIds(listOf("shared", "a1", "b1")).associateBy { it.sourceId }
        assertNull("ownership follows the snapshot: the main's now", rows.getValue("shared").spotifyAccount)
        assertNull(rows.getValue("a1").spotifyAccount)
        assertEquals(userB, rows.getValue("b1").spotifyAccount)
    }

    @Test fun `albums both accounts saved are claimed by the main too`() = runBlocking {
        addExtra(userB)
        library(null).albums += album("shared")
        library(userB).albums += listOf(album("shared"), album("bonly"))

        val syncId = runFetch(withAlbums = true)

        val snapshots = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId)
        assertNull(snapshots.single { it.sourcePlaylistId == albumKey("shared") }.spotifyAccount)
        assertEquals(userB, snapshots.single { it.sourcePlaylistId == albumKey("bonly") }.spotifyAccount)
        coVerify(exactly = 1) { spotify.getAlbumTracks(album("shared")) }
        val bStep = step(syncId, "getSavedAlbums", "extra 1")
        assertEquals("both count as B's listing", 2, bStep.itemCount)
        assertTrue(bStep.note!!.contains("1 also saved on an account read before, written there"))
    }

    @Test fun `the main's listing missing a page holds its rows - an extra that lists one neither re-reads nor takes it`() = runBlocking {
        addExtra(userB)
        library(null).playlists += "a1"
        library(null).listingPartial = true
        library(userB).playlists += listOf("shared", "b1")
        // The main's: on the part of its library this sync couldn't list.
        val shared = insertRow("shared")
        val aGone = insertRow("a-gone")
        val bGone = insertRow("b-gone", owner = userB)

        val syncId = runFetch()

        assertTrue("B didn't snapshot it", db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).none { it.sourcePlaylistId == "shared" })
        coVerify(exactly = 0) { spotify.getPlaylistTracks("shared") }
        assertEquals("1 also on an account read before, read there", step(syncId, "getUserPlaylists", "extra 1").note)
        runDiff(syncId)

        val row = db.playlistDao().getById(shared)!!
        assertNull("still the main's", row.spotifyAccount)
        assertTrue(row.isActive)
        assertTrue("the main's listing missed a page: nothing is hidden", db.playlistDao().getById(aGone)!!.isActive)
        assertTrue(db.playlistDao().getById(bGone)!!.isActive)
    }

    @Test fun `a row moves away from its account only once that account listed everything without it`() = runBlocking {
        addExtra(userB)
        addExtra(userC)
        library(userB).playlists += "t"
        library(userC).playlists += "c1"
        library(userC).listingPartial = true
        val t = insertRow("t", owner = userC)

        runDiff(runFetch())

        assertEquals("C's listing missed a page: still C's", userC, db.playlistDao().getById(t)!!.spotifyAccount)
        assertEquals("its songs still came from B's read", listOf("t-1", "t-2"), titlesIn(t))

        library(userC).listingPartial = false
        tokens.dead += userC
        runDiff(runFetch())

        assertEquals("C signed out: still C's", userC, db.playlistDao().getById(t)!!.spotifyAccount)

        tokens.dead -= userC
        runDiff(runFetch())

        assertEquals("C listed everything, and not t: B's now", userB, db.playlistDao().getById(t)!!.spotifyAccount)
        assertTrue(db.playlistDao().getById(t)!!.isActive)
    }

    @Test fun `a row whose account is gone goes to the account that lists it`() = runBlocking {
        addExtra(userB)
        library(userB).playlists += "o"
        val o = insertRow("o", owner = "ghost")

        runDiff(runFetch())

        assertEquals(userB, db.playlistDao().getById(o)!!.spotifyAccount)
    }

    // ── Liked Songs ──────────────────────────────────────────────────────

    @Test fun `two accounts give two Liked rows, and Refresh of B never removes A's likes`() = runBlocking {
        addExtra(userB)
        library(null).liked += listOf("A1", "A2")
        library(userB).liked += listOf("B1")

        runDiff(runFetch())

        val main = db.playlistDao().findBySourceId(SpotifyPlaylistIds.MAIN_LIKED_SONGS)!!
        val extra = db.playlistDao().findBySourceId(SpotifyPlaylistIds.extraLikedSongs(userB))!!
        assertNull(main.spotifyAccount)
        assertEquals(userB, extra.spotifyAccount)
        assertEquals(PlaylistType.LIKED_SONGS, extra.type)
        assertEquals(listOf("A1", "A2"), titlesIn(main.id))
        assertEquals(listOf("B1"), titlesIn(extra.id))

        // B un-likes B1 and likes B2; A changes nothing.
        library(userB).liked.apply { clear(); add("B2") }
        runDiff(runFetch())

        assertEquals("A's likes untouched by B's Refresh", listOf("A1", "A2"), titlesIn(main.id))
        assertEquals(listOf("B2"), titlesIn(extra.id))
    }

    // ── Containment ──────────────────────────────────────────────────────

    @Test fun `a 429 in an extra's pass leaves the main's results in place and doesn't retry the chain`() = runBlocking {
        addExtra(userB, mixes = true)
        library(null).playlists += "a1"
        library(null).liked += "A1"
        library(userB).mixes += "bmix"
        library(userB).playlists += "b1"
        songs["bmix"] = listOf("M1")
        coEvery { spotify.getPlaylistTracks("bmix") } throws SpotifyApiException(429, "https://api.spotify.com/v1/x/$userB", "HTTP 429 for $userB")
        // Rows a whole run would hide: B's listing never finished, so nothing goes.
        val aGone = insertRow("a-gone")
        val bGone = insertRow("b-gone", owner = userB)

        val result = runFetchResult()

        assertTrue("no retry of the chain: $result", result is ListenableWorker.Result.Success)
        val syncId = (result as ListenableWorker.Result.Success).outputData.getLong(PlaylistFetchWorker.KEY_SYNC_ID, -1)
        val snapshots = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).map { it.sourcePlaylistId }
        assertTrue("the main's results stand", snapshots.containsAll(listOf("a1", SpotifyPlaylistIds.MAIN_LIKED_SONGS)))
        assertFalse("B's pass stopped at its mixes", "b1" in snapshots)
        val failed = step(syncId, "accountPass", "extra 1")
        assertEquals(StepStatus.ERROR, failed.status)
        assertEquals(429, failed.httpCode)
        assertFalse("never the account's id", failed.errorMessage!!.contains(userB))
        assertTrue("one incomplete listing hides nothing for anyone", db.playlistDao().getById(aGone)!!.isActive)
        assertTrue(db.playlistDao().getById(bGone)!!.isActive)
    }

    @Test fun `a 429 on the main account retries the chain as before, and no extra account is read`() = runBlocking {
        every { prefs.discoverAutoMixes } returns flowOf(true)
        addExtra(userB)
        library(null).mixes += "amix"
        library(userB).playlists += "b1"
        coEvery { spotify.getPlaylistTracks("amix") } throws SpotifyApiException(429, "url", "rate limited")

        val result = runFetchResult()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertFalse("B's sign-in wasn't even asked for", userB in tokens.asked)
        assertTrue(db.playlistDao().findBySourceIds(listOf("b1")).isEmpty())
    }

    @Test fun `a 429 the main's pass met and swallowed keeps every extra for the next sync`() = runBlocking {
        addExtra(userB)
        library(null).playlists += "a1"
        library(userB).playlists += "b1"
        val bGone = insertRow("b-gone", owner = userB)
        // The main's Liked Songs page gets a 429: the read reports a failed page, nothing thrown.
        coEvery { spotify.getLikedSongs(any(), any()) } answers {
            rateLimited++
            SyncResult.Error("HTTP 429")
        }

        val syncId = runFetch()

        assertFalse("B's sign-in wasn't even asked for", userB in tokens.asked)
        val held = step(syncId, "accountPass", "extra 1")
        assertEquals(StepStatus.SKIPPED, held.status)
        assertTrue(held.note!!.contains("HTTP 429"))
        assertTrue(db.playlistDao().getById(bGone)!!.isActive)
        assertTrue("the main's results stand", db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).any { it.sourcePlaylistId == "a1" })
    }

    @Test fun `the main's album reads failing until they stop keeps every extra for the next sync`() = runBlocking {
        addExtra(userB)
        library(null).albums += listOf(album("a1"), album("a2"), album("a3"))
        library(userB).playlists += "b1"
        coEvery { spotify.getAlbumTracks(any()) } returns null

        val syncId = runFetch(withAlbums = true)

        assertFalse(userB in tokens.asked)
        assertTrue(step(syncId, "accountPass", "extra 1").note!!.contains("album reads kept failing"))
    }

    // ── Who runs ─────────────────────────────────────────────────────────

    @Test fun `a signed-out extra is skipped, its rows untouched, and it signs back in by itself`() = runBlocking {
        val bRow = addExtra(userB)
        library(null).playlists += "a1"
        library(userB).playlists += "b1"
        val bOld = insertRow("b-old", owner = userB)
        tokens.dead += userB

        val first = runFetch()

        assertTrue("not in B's listing, but B didn't run", db.playlistDao().getById(bOld)!!.isActive)
        assertTrue(db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(first).none { it.spotifyAccount != null })
        assertNotNull(db.spotifyAccountDao().getById(bRow)!!.signedOutAt)
        val signIn = step(first, "signIn", "extra 1")
        assertEquals(StepStatus.ERROR, signIn.status)
        assertEquals("asked once, before the pass", 1, tokens.asked.count { it == userB })

        tokens.dead -= userB
        val second = runFetch()

        assertNull("works again: \"Sign in again\" goes", db.spotifyAccountDao().getById(bRow)!!.signedOutAt)
        assertEquals(userB, db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(second).single { it.sourcePlaylistId == "b1" }.spotifyAccount)
        assertFalse("B's own complete listing hides its own gone row", db.playlistDao().getById(bOld)!!.isActive)
    }

    @Test fun `a sync reads at most three extra accounts, in row-id order`() = runBlocking {
        val users = listOf("u1", "u2", "u3", "u4")
        for (u in users) {
            addExtra(u)
            library(u).playlists += "p-$u"
        }
        val u4Old = insertRow("u4-old", owner = "u4")

        val syncId = runFetch()

        val tagged = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).mapNotNull { it.spotifyAccount }.toSet()
        assertEquals(setOf("u1", "u2", "u3"), tagged)
        assertFalse("u4" in tokens.asked)
        assertEquals(StepStatus.SKIPPED, step(syncId, "accountLimit", "extra 4").status)
        assertTrue("the fourth's rows are left alone", db.playlistDao().getById(u4Old)!!.isActive)
    }

    @Test fun `an extra's switches are its own, mixes off by default`() = runBlocking {
        addExtra(userB, playlists = false, liked = false, albums = false)
        library(userB).mixes += "bmix"
        library(userB).playlists += "b1"
        library(userB).liked += "B1"
        library(userB).albums += album("b")
        val bOld = insertRow("b-old", owner = userB)

        val syncId = runFetch(withAlbums = true)

        coVerify(exactly = 0) { spotify.getDailyMixes() }
        for (kind in listOf("getDailyMixes", "getLikedSongs", "getUserPlaylists", "getSavedAlbums")) {
            assertEquals(kind, StepStatus.SKIPPED, step(syncId, kind, "extra 1").status)
        }
        assertTrue(db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).none { it.spotifyAccount == userB })
        assertTrue("B's playlists switched off: its rows are left alone", db.playlistDao().getById(bOld)!!.isActive)
    }

    // ── The joint hide, end to end ───────────────────────────────────────

    @Test fun `B's complete listing never hides A's rows, and Undo after a sync that hid rows of both restores both`() = runBlocking {
        addExtra(userB)
        library(null).playlists += "a-kept"
        library(userB).playlists += "b-kept"
        val aKept = insertRow("a-kept")
        val aGone = insertRow("a-gone")
        val bKept = insertRow("b-kept", owner = userB)
        val bGone = insertRow("b-gone", owner = userB)

        val syncId = runFetch()

        assertTrue(db.playlistDao().getById(aKept)!!.isActive)
        assertTrue(db.playlistDao().getById(bKept)!!.isActive)
        assertFalse(db.playlistDao().getById(aGone)!!.isActive)
        assertFalse(db.playlistDao().getById(bGone)!!.isActive)
        runDiff(syncId)

        db.syncUndoDao().restore(syncId)

        assertTrue(db.playlistDao().getById(aGone)!!.isActive)
        assertTrue(db.playlistDao().getById(bGone)!!.isActive)
    }

    @Test fun `an extra's playlist it can't read keeps the extra's rows, and the main's gone rows still go`() = runBlocking {
        addExtra(userB)
        library(null).playlists += "a-kept"
        library(userB).playlists += listOf("b-kept", "b-broken")
        coEvery { spotify.getPlaylistTracks("b-broken") } returns SyncResult.Error("nope")
        val aGone = insertRow("a-gone")
        val bGone = insertRow("b-gone", owner = userB)

        val syncId = runFetch()

        assertFalse("every account listed every id, and the main read all of its own", db.playlistDao().getById(aGone)!!.isActive)
        assertTrue("B couldn't read one of its playlists: B's own rows wait", db.playlistDao().getById(bGone)!!.isActive)
        assertEquals(StepStatus.ERROR, step(syncId, "getUserPlaylists", "extra 1").status)
    }

    @Test fun `an album the joint hide takes out is named on its account's albums step`() = runBlocking {
        addExtra(userB)
        library(userB).albums += album("kept")
        insertRow(albumKey("gone"), owner = userB)

        val syncId = runFetch(withAlbums = true)

        assertTrue(step(syncId, "getSavedAlbums", "extra 1").note!!.contains("1 un-saved on Spotify, hidden"))
        assertNull(step(syncId, "getSavedAlbums", null).note)
    }

    // ── A background sync's time ─────────────────────────────────────────

    @Test fun `in a background sync out of time, an extra waits for the next sync and holds nobody back`() = runBlocking {
        addExtra(userB)
        library(null).playlists += "a-kept"
        library(userB).playlists += "b1"
        val aGone = insertRow("a-gone")
        val bGone = insertRow("b-gone", owner = userB)
        takesMinutes("a-kept", 7)

        val syncId = runFetch()

        assertFalse("B's sign-in wasn't even asked for", userB in tokens.asked)
        val skipped = step(syncId, "accountPass", "extra 1")
        assertEquals(StepStatus.SKIPPED, skipped.status)
        assertTrue(skipped.note!!.startsWith("not read this sync"))
        assertTrue("B's rows are left as they are", db.playlistDao().getById(bGone)!!.isActive)
        assertFalse("the main's hide isn't held back", db.playlistDao().getById(aGone)!!.isActive)
        assertNull("not taken for signed out", db.spotifyAccountDao().getByUserId(userB)!!.signedOutAt)
    }

    @Test fun `an extra running out of time stops before its next read, keeps what it read, and holds nobody back`() = runBlocking {
        addExtra(userB)
        library(null).playlists += "a-kept"
        library(userB).playlists += listOf("b1", "b2")
        val aGone = insertRow("a-gone")
        val bGone = insertRow("b-gone", owner = userB)
        takesMinutes("b1", 7)

        val syncId = runFetch()

        coVerify(exactly = 0) { spotify.getPlaylistTracks("b2") }
        val snapshots = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).associateBy { it.sourcePlaylistId }
        assertEquals("what it read stays", userB, snapshots.getValue("b1").spotifyAccount)
        assertFalse("b2" in snapshots)
        val stopped = step(syncId, "accountPass", "extra 1")
        assertEquals(StepStatus.SKIPPED, stopped.status)
        assertTrue(stopped.note!!.startsWith("stopped part way"))
        assertTrue("B's own rows wait for a whole read", db.playlistDao().getById(bGone)!!.isActive)
        assertFalse("B listed every id: the main's hide goes ahead", db.playlistDao().getById(aGone)!!.isActive)

        runDiff(syncId)

        assertEquals(listOf("b1-1", "b1-2"), titlesIn(db.playlistDao().findBySourceId("b1")!!.id))
    }

    @Test fun `a sync in the foreground has no time limit`() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        addExtra(userB)
        library(null).playlists += "a-kept"
        library(userB).playlists += listOf("b1", "b2")
        takesMinutes("a-kept", 30)

        val syncId = runFetch(foreground = true)

        val tagged = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).filter { it.spotifyAccount == userB }
        assertEquals(setOf("b1", "b2"), tagged.map { it.sourcePlaylistId }.toSet())
    }

    // ── Diff: Home, Download, removal ────────────────────────────────────

    @Test fun `an extra's new playlist is not pinned to Home after Stream everything, and starts with Download off`() = runBlocking {
        every { prefs.pinNewPlaylists } returns flowOf(true)
        addExtra(userB)
        library(null).playlists += "a1"
        library(userB).playlists += "b1"

        runDiff(runFetch())

        val rows = db.playlistDao().findBySourceIds(listOf("a1", "b1")).associateBy { it.sourceId }
        assertNotNull("the main's still is", rows.getValue("a1").pinnedToHomeAt)
        assertNull(rows.getValue("b1").pinnedToHomeAt)
        assertFalse(rows.getValue("b1").syncEnabled)
        assertFalse(rows.getValue("b1").keepOffline)
    }

    @Test fun `an account removed during a sync stays removed, and adding it again brings its rows back`() = runBlocking {
        val bRow = addExtra(userB)
        library(userB).playlists += "b1"
        runDiff(runFetch())
        val b1 = db.playlistDao().findBySourceId("b1")!!.id

        // Removed between the fetch and the diff.
        val syncId = runFetch()
        SpotifyExtraAccountRemover(db, tokens).remove(bRow)
        runDiff(syncId)

        assertFalse("its last pass doesn't bring it back", db.playlistDao().getById(b1)!!.isActive)

        addExtra(userB)
        runDiff(runFetch())

        val back = db.playlistDao().getById(b1)!!
        assertTrue("re-adopted: the same row, back when listed again", back.isActive)
        assertEquals(userB, back.spotifyAccount)
    }

    @Test fun `ownership moves only between the Spotify sync's own rows, and an extra's row is never pinned`() {
        val snapshot = RemotePlaylistSnapshotEntity(
            syncId = 1, source = MusicSource.SPOTIFY, sourcePlaylistId = "p", playlistName = "p",
            playlistType = PlaylistType.CUSTOM, spotifyAccount = userB,
        )
        val synced = PlaylistEntity(name = "p", source = MusicSource.SPOTIFY, sourceId = "p", type = PlaylistType.CUSTOM)
        assertTrue(movesToSnapshotAccount(synced, snapshot))
        assertFalse(movesToSnapshotAccount(synced.copy(spotifyAccount = userB), snapshot))
        assertFalse("a pasted copy is the listener's own", movesToSnapshotAccount(synced.copy(source = MusicSource.BOTH), snapshot))
        assertFalse(movesToSnapshotAccount(synced, snapshot.copy(source = MusicSource.YOUTUBE)))
        assertTrue("back to the main", movesToSnapshotAccount(synced.copy(spotifyAccount = userB), snapshot.copy(spotifyAccount = null)))

        assertTrue(pinOnDiscovery(PlaylistType.CUSTOM, true, true, MusicSource.SPOTIFY, "p"))
        assertFalse(pinOnDiscovery(PlaylistType.CUSTOM, true, true, MusicSource.SPOTIFY, "p", spotifyAccount = userB))
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    @Test fun `steps carry the extra's label, never its id or its name`() = runBlocking {
        val secretId = "secretuserid42"
        addExtra(secretId, label = "Sam Secretname")
        library(secretId).playlists += "s1"
        library(secretId).liked += "S1"
        library(null).playlists += "a1"

        val syncId = runFetch(withAlbums = true)

        val json = db.syncHistoryDao().getById(syncId)!!.diagnostics!!
        assertFalse(json.contains(secretId, ignoreCase = true))
        assertFalse(json.contains("Secretname"))
        val steps = Json.decodeFromString<List<SyncStepResult>>(json)
        val extraSteps = steps.filter { it.account != null }.map { it.step }.toSet()
        assertEquals(setOf("getDailyMixes", "getLikedSongs", "getUserPlaylists", "getSavedAlbums"), extraSteps)
        assertTrue(steps.filter { it.account != null }.all { it.account == "extra 1" })
        assertTrue("the main's steps carry no account", steps.filter { it.account == null }.map { it.step }.containsAll(extraSteps - "getDailyMixes"))
    }

    @Test fun `with no extra accounts a sync records exactly what it did before`() = runBlocking {
        library(null).playlists += "a1"

        val syncId = runFetch(withAlbums = true)

        assertFalse(db.syncHistoryDao().getById(syncId)!!.diagnostics!!.contains("\"account\""))
        assertTrue(tokens.asked.isEmpty())
    }

    // ── Wiring: the acting account reaches the network ───────────────────

    @Test fun `each pass reads its own account's library through the real client`() = runBlocking {
        addExtra(userB)
        libraryByToken["token-main"] = listOf("amain")
        libraryByToken["token-$userB"] = listOf("bonly")
        useRealClient()

        val syncId = runFetch()

        val snapshots = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId).associateBy { it.sourcePlaylistId }
        assertNull(snapshots.getValue("amain").spotifyAccount)
        assertEquals(userB, snapshots.getValue("bonly").spotifyAccount)
        assertEquals(setOf("token-main", "token-$userB"), libraryBearers.toSet())
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private suspend fun addExtra(
        userId: String,
        label: String = "Account $userId",
        playlists: Boolean = true,
        liked: Boolean = true,
        albums: Boolean = true,
        mixes: Boolean = false,
    ): Long = db.spotifyAccountDao().insert(
        SpotifyAccountEntity(
            userId = userId, label = label, addedAt = System.currentTimeMillis(),
            syncPlaylists = playlists, syncLiked = liked, syncAlbums = albums, syncMixes = mixes,
        ),
    )

    private suspend fun insertRow(sourceId: String, owner: String? = null): Long = db.playlistDao().insert(
        PlaylistEntity(name = sourceId, source = MusicSource.SPOTIFY, sourceId = sourceId, type = PlaylistType.CUSTOM, spotifyAccount = owner),
    )

    private fun songsOf(id: String) = songs[id] ?: listOf("$id-1", "$id-2")

    /** Reading playlist [id]'s songs takes [minutes] on the worker's clock. */
    private fun takesMinutes(id: String, minutes: Long) {
        coEvery { spotify.getPlaylistTracks(id) } answers {
            clock.now += minutes * 60_000
            SyncResult.Success(SpotifyPlaylistTracks(songsOf(id).map(::track), countVerified = true, revisionId = revision))
        }
    }

    private fun listed(id: String) = SpotifyPlaylistItem(
        id = id,
        name = "Playlist $id",
        owner = SpotifyOwner(id = "someone"),
        tracks = SpotifyTracksRef(total = songsOf(id).size),
        revisionId = revision,
    )

    private fun track(title: String) = SpotifyTrackItem(
        track = SpotifyTrackObject(
            id = title, name = title,
            artists = listOf(SpotifyArtist(id = "artist", name = "Artist")),
            uri = "spotify:track:$title",
        ),
    )

    private fun album(name: String) = name.padEnd(22, 'x')
    private fun albumKey(name: String) = "album:spotify:" + album(name)

    private fun savedAlbum(id: String) = SpotifySavedAlbum(
        id = id, name = "Album $id", artists = listOf("Artist"), coverUrl = null,
        addedAt = "2026-10-11T10:00:00Z", albumType = "ALBUM",
    )

    private suspend fun titlesIn(playlistId: Long): List<String> {
        val refs = db.playlistDao().getCrossRefsForPlaylist(playlistId).filter { it.removedAt == null }.sortedBy { it.position }
        val tracks = db.trackDao().getByIds(refs.map { it.trackId }).associateBy { it.id }
        return refs.map { tracks.getValue(it.trackId).title }
    }

    private suspend fun step(syncId: Long, name: String, account: String?): SyncStepResult =
        Json.decodeFromString<List<SyncStepResult>>(db.syncHistoryDao().getById(syncId)!!.diagnostics!!)
            .single { it.step == name && it.account == account }

    // ── The real client, over a faked HTTP layer ─────────────────────────

    /** The playlist ids `libraryV3` answers, by the bearer token the request carried. */
    private val libraryByToken = HashMap<String, List<String>>()
    private val libraryBearers = mutableListOf<String>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = if ("operationName=libraryV3" in request.url.toString()) {
            val bearer = request.header("Authorization").orEmpty().removePrefix("Bearer ")
            libraryBearers += bearer
            libraryJson(libraryByToken[bearer].orEmpty())
        } else {
            "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun libraryJson(ids: List<String>): String {
        val items = ids.joinToString(",") { id ->
            """{"item":{"__typename":"PlaylistResponseWrapper","data":{"__typename":"Playlist",""" +
                """"uri":"spotify:playlist:$id","name":"Playlist $id",""" +
                """"ownerV2":{"data":{"__typename":"User","username":"someone"}},"images":null,""" +
                """"content":{"totalCount":2}}}}"""
        }
        return """{"data":{"me":{"libraryV3":{"totalCount":${ids.size},"items":[$items]}}}}"""
    }

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken(any()) } returns "client-token"
        coEvery { getClientCredentialsToken() } returns null
        every { scrapePersistedQueryHash(any(), any()) } returns null
    }

    /** The library listing goes through the real [SpotifyApiClient] and [tokens]; the rest stays faked. */
    private fun useRealClient() {
        spotifyClient = spyk(SpotifyApiClient(http, tokens, authManager)).also { real ->
            coEvery { real.getLikedSongs(any(), any()) } returns SyncResult.Empty("none")
            coEvery { real.getPlaylistTracks(any()) } answers {
                SyncResult.Success(SpotifyPlaylistTracks(listOf(track("T1"), track("T2")), countVerified = true, revisionId = revision))
            }
        }
    }

    // ── Runners ──────────────────────────────────────────────────────────

    private suspend fun runFetch(withAlbums: Boolean = false, foreground: Boolean = false): Long {
        val result = runFetchResult(withAlbums, foreground)
        assertTrue("fetch must succeed: $result", result is ListenableWorker.Result.Success)
        return (result as ListenableWorker.Result.Success).outputData.getLong(PlaylistFetchWorker.KEY_SYNC_ID, -1L)
    }

    /** One fetch; in the background (no foreground service, so the time limit applies) unless [foreground]. */
    private suspend fun runFetchResult(withAlbums: Boolean = false, foreground: Boolean = false): ListenableWorker.Result {
        // Each sync starts with the main account's pass, which asks the fake client for nothing
        // that reads a token: the account the last sync's last pass acted as must not carry over.
        tokens.actingNow = null
        return TestListenableWorkerBuilder<PlaylistFetchWorker>(context)
            .apply { if (!foreground) setForegroundUpdater(refusesForeground) }
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = PlaylistFetchWorker(
                    appContext, workerParameters,
                    tokenManager = tokens,
                    spotifyApiClient = spotifyClient,
                    ytMusicApiClient = mockk(relaxed = true),
                    innerTubeClient = mockk(relaxed = true),
                    playlistDao = db.playlistDao(),
                    syncHistoryDao = db.syncHistoryDao(),
                    remoteSnapshotDao = db.remoteSnapshotDao(),
                    syncStateManager = mockk(relaxed = true),
                    syncLog = syncLog,
                    syncNotificationManager = mockk(relaxed = true),
                    syncPreferencesManager = prefs,
                    spotifyAuthHealthProbe = probe,
                    youtubeAuthHealthProbe = mockk(relaxed = true),
                    streamingPreference = streamingPreference,
                    syncUndoDao = db.syncUndoDao(),
                    spotifySavedAlbums = if (withAlbums) SpotifySavedAlbumsFetcher(spotifyClient, db, prefs, syncLog) else null,
                    spotifyAccountDao = db.spotifyAccountDao(),
                    syncClock = clock,
                )
            })
            .build()
            .doWork()
    }

    private suspend fun runDiff(syncId: Long) {
        val result = TestListenableWorkerBuilder<DiffWorker>(context)
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
                    syncPreferencesManager = prefs,
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
        assertTrue("diff must succeed: $result", result is ListenableWorker.Result.Success)
    }
}

/**
 * A [TokenManager] that answers per acting account, as the real one does: the main account
 * ("token-main") with no [SpotifyAccount] in the context, an extra's own token ("token-<userId>")
 * with one, and null for an extra in [dead]. Each token read records the acting account
 * ([actingNow], [asked]).
 */
private class ActingTokenManager(base: TokenManager) : TokenManager by base {
    /** The account the last token read acted as: null for the main. */
    @Volatile var actingNow: String? = null
    val asked: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())
    val dead: MutableSet<String> = HashSet()

    override suspend fun getSpotifyAccessToken(): String? {
        val acting = currentCoroutineContext()[SpotifyAccount]?.userId
        actingNow = acting
        asked += acting
        return if (acting != null && acting in dead) null else "token-${acting ?: "main"}"
    }

    override suspend fun forceRefreshSpotifyAccessToken(): String? = getSpotifyAccessToken()

    override suspend fun getSpotifyClientId(): String = "client-id"

    override suspend fun isAuthenticated(service: AuthService): Boolean = service == AuthService.SPOTIFY

    override suspend fun clearSpotifyExtra(rowId: Long) = Unit
}
