package com.stash.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A song already on the phone is never downloaded a second time (duplicates, cause 3): a tap on
 * Download and a playlist's Download switch both ask [com.stash.core.data.library.findLibraryCopy]
 * first. The owner's case: "Starting Over" downloaded by hand (a FLAC, liked), and an id-less red
 * stub of the same song in a Stash Mix. Real DB, real rows; nothing is ever deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryLibraryCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

    /** What the stand-in drain waits on, held here so the collector never fails it mid-test. */
    private val neverDone = CompletableDeferred<ListenableWorker.Result>()

    @Before fun setUp() {
        // The real drain needs Hilt; this stand-in never finishes (MusicRepositoryQueuePlaylistTest).
        val neverFinishes = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): ListenableWorker.Result = neverDone.await()
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(neverFinishes).build(),
        )
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
    }

    // ── A tap on Download ──────────────────────────────────────────────────

    @Test fun `a tap on a stub of a song downloaded by hand downloads nothing and says so`() = runTest {
        val handDownload = song("Starting Over", youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        val stub = song("Starting Over") // id-less discovery stub, red dot

        val repo = repo()
        assertEquals(DownloadRequestResult.ALREADY_ON_PHONE, repo.requestDownload(stub))
        assertFalse("queueDownload reports nothing queued", repo.queueDownload(stub))

        assertNull(db.downloadQueueDao().getByTrackId(stub))
        verify(exactly = 0) { enqueuer.enqueue(any()) }
        // Nothing changed on either row: the stub keeps no file, the copy keeps its own.
        assertFalse(db.trackDao().getById(stub)!!.isDownloaded)
        assertEquals("/music/starting-over.flac", db.trackDao().getById(handDownload)!!.filePath)
    }

    @Test fun `another version or a clearly different length still downloads`() = runTest {
        song("Starting Over", file = "/music/starting-over.flac")
        val live = song("Starting Over (Live)")
        val longer = song("Starting Over", lengthMs = 480_000L)

        val repo = repo()
        assertEquals(DownloadRequestResult.QUEUED, repo.requestDownload(live))
        assertEquals(DownloadRequestResult.QUEUED, repo.requestDownload(longer))
        assertNotNull(db.downloadQueueDao().getByTrackId(live))
        assertNotNull(db.downloadQueueDao().getByTrackId(longer))
    }

    @Test fun `with no copy on the phone a tap downloads as before`() = runTest {
        val stub = song("Starting Over")
        assertEquals(DownloadRequestResult.QUEUED, repo().requestDownload(stub))
        verify(exactly = 1) { enqueuer.enqueue(any()) }
    }

    // ── A playlist's Download switch ───────────────────────────────────────

    @Test fun `the Download switch links the phone's copy where the song sits instead of downloading it`() = runTest {
        val handDownload = song("Starting Over", youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        val pid = playlist("Daily Discover", PlaylistType.STASH_MIX)
        val fresh = member(pid, 0, song("Never Heard"))
        val stub = member(pid, 3, song("Starting Over"))

        val repo = repo()
        assertEquals("only the song not on the phone is queued", 1, repo.queueDownloadsForPlaylist(pid, background = true))

        assertEquals(listOf(fresh), db.downloadQueueDao().pendingDiscoveryDownloads().map { it.trackId })
        val link = db.playlistDao().getCrossRef(pid, handDownload)
        assertNotNull("the copy is linked into the playlist", link)
        assertEquals("where the song sits", 3, link!!.position)
        assertNull(link.removedAt)
        assertNotNull("the stub's own membership is kept: nothing is deleted", db.playlistDao().getCrossRef(pid, stub))

        // Safe to run after every sync: a second pass queues and links nothing more.
        assertEquals(0, repo.queueDownloadsForPlaylist(pid, background = true))
        assertEquals(1, db.downloadQueueDao().pendingDiscoveryDownloads().size)
        assertEquals(3, db.playlistDao().getCrossRefsForPlaylist(pid).size)
    }

    @Test fun `a copy the user took out of the playlist does not stand in, so the song downloads`() = runTest {
        val handDownload = song("Starting Over", file = "/music/starting-over.flac")
        val pid = playlist("Road Trip", PlaylistType.CUSTOM)
        val stub = member(pid, 0, song("Starting Over"))
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = pid, trackId = handDownload, position = 1, removedAt = java.time.Instant.now()),
        )

        assertEquals(1, repo().queueDownloadsForPlaylist(pid, background = true))

        assertEquals(listOf(stub), db.downloadQueueDao().pendingDiscoveryDownloads().map { it.trackId })
        assertNotNull("still removed", db.playlistDao().getCrossRef(pid, handDownload)!!.removedAt)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** A row as the library stores it; [file] makes it a download. */
    private suspend fun song(
        title: String,
        youtubeId: String? = null,
        file: String? = null,
        lengthMs: Long = 304_000L,
    ): Long = db.trackDao().insert(
        TrackEntity(
            title = title,
            artist = "LSD and the Search for God",
            durationMs = lengthMs,
            youtubeId = youtubeId,
            filePath = file,
            isDownloaded = file != null,
            isStreamable = true,
            source = MusicSource.YOUTUBE,
            canonicalTitle = TrackCanonical.title(title),
            canonicalArtist = TrackCanonical.artist("LSD and the Search for God"),
        ),
    )

    private suspend fun playlist(name: String, type: PlaylistType): Long = db.playlistDao().insert(
        PlaylistEntity(name = name, source = MusicSource.BOTH, sourceId = "custom_$name", type = type, keepOffline = true),
    )

    private suspend fun member(playlistId: Long, position: Int, trackId: Long): Long {
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = position))
        return trackId
    }

    private fun repo(): MusicRepositoryImpl {
        val network = mockk<DownloadNetworkPreference>()
        coEvery { network.current() } returns DownloadNetworkMode.WIFI_AND_CHARGING
        val streaming = mockk<StreamingPreference>()
        coEvery { streaming.current() } returns false
        val sync = mockk<SyncPreferencesManager>(relaxed = true)
        every { sync.preferences } returns flowOf(SyncPreferences(wifiOnly = true))
        return MusicRepositoryImpl(
            context = context,
            trackDao = db.trackDao(),
            playlistDao = db.playlistDao(),
            syncHistoryDao = mockk(relaxed = true),
            downloadQueueDao = db.downloadQueueDao(),
            discoveryQueueDao = mockk(relaxed = true),
            blocklistGuard = mockk(relaxed = true),
            trackMatcher = mockk(relaxed = true),
            stashMixRecipeDao = mockk(relaxed = true),
            downloadNetworkPreference = network,
            streamingPreference = streaming,
            libraryScopePreference = mockk(relaxed = true),
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = sync,
            singleTrackDownloadEnqueuer = enqueuer,
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
            downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
        )
    }
}
