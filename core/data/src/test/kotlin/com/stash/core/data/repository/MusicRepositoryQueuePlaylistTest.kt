package com.stash.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.workers.DiscoveryDownloadWorker
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MusicRepositoryImpl.queueDownloadsForPlaylist] now runs after every sync for a
 * kept playlist (#474), so it has to be a true no-op the second time: a song that
 * already failed, was cancelled, or had its match dismissed keeps its one row
 * instead of getting a fresh one per call. Real DB, real rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryQueuePlaylistTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    @Before fun setUp() {
        // The real drain needs Hilt. This stand-in never finishes, so a test can hold a drain RUNNING.
        val neverFinishes = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): ListenableWorker.Result = awaitCancellation()
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
        // Stop any stand-in worker a test left RUNNING, then close this WorkManager's database, while both
        // are alive. Left to the garbage collector, they fail later, on a closed connection, in another test.
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
    }

    @Test fun `queues only never-tried songs, and a second call adds nothing`() = runTest {
        val pid = db.playlistDao().insert(
            PlaylistEntity(name = "Kept", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM),
        )
        val fresh = member(pid, 0, track("Fresh"))
        val failed = member(pid, 1, track("Failed"))
        val cancelled = member(pid, 2, track("Cancelled"))
        member(pid, 3, track("Dismissed").copy(matchDismissed = true))
        member(pid, 4, track("Downloaded").copy(isDownloaded = true, filePath = "/x.flac"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = failed, status = DownloadStatus.FAILED, retryCount = 3))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = cancelled, status = DownloadStatus.SKIPPED))

        val repo = repo()
        assertEquals(1, repo.queueDownloadsForPlaylist(pid))
        assertEquals(0, repo.queueDownloadsForPlaylist(pid))

        val pending = db.downloadQueueDao().pendingDiscoveryDownloads().filter { it.status == DownloadStatus.PENDING }
        assertEquals(listOf(fresh), pending.map { it.trackId })
    }

    @Test fun `a sync queues every kept playlist, then starts one drain that follows Wi-Fi only`() = runTest {
        member(playlist("Kept 1", keepOffline = true), 0, track("A"))
        member(playlist("Kept 2", keepOffline = true), 0, track("B"))
        member(playlist("Not kept"), 0, track("C"))

        assertEquals(2, repo().queueKeptPlaylists())

        val waiting = db.downloadQueueDao().pendingDiscoveryDownloads().map { db.trackDao().getById(it.trackId)!!.title }
        assertEquals(setOf("A", "B"), waiting.toSet())
        // One drain for both playlists (a drain per playlist restarts it), on Wi-Fi as the Sync tab says.
        assertEquals(listOf(NetworkType.UNMETERED), drains().map { it.constraints.requiredNetworkType })
    }

    /**
     * Background downloads follow the Sync tab's "Wi-Fi only", the setting a sync's
     * own downloads use. "Run recommendations when" (DownloadNetworkMode) sits at its
     * default, Wi-Fi + charging, in [repo]: none of it may leak in, charging included.
     */
    @Test fun `with Wi-Fi only on, background downloads wait for Wi-Fi and never for a charger`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo(wifiOnly = true).queueDownloadsForPlaylist(pid, background = true)
        assertEquals(listOf(NetworkType.UNMETERED to false), drains().map(::networkAndCharging))
    }

    @Test fun `with Wi-Fi only off, background downloads use any network`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo(wifiOnly = false).queueDownloadsForPlaylist(pid, background = true)
        assertEquals(listOf(NetworkType.CONNECTED to false), drains().map(::networkAndCharging))
    }

    private fun networkAndCharging(info: WorkInfo) =
        info.constraints.requiredNetworkType to info.constraints.requiresCharging()

    @Test fun `a tap after a background drain takes over on any network`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo().queueDownloadsForPlaylist(pid, background = true)
        member(pid, 1, track("B"))
        repo().queueDownloadsForPlaylist(pid)

        val live = drains().filter { !it.state.isFinished }
        assertEquals(listOf(NetworkType.CONNECTED), live.map { it.constraints.requiredNetworkType })
    }

    @Test fun `a background start leaves a waiting tap drain alone`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo().queueDownloadsForPlaylist(pid)
        member(pid, 1, track("B"))
        repo().queueDownloadsForPlaylist(pid, background = true)

        // The tap's drain hasn't started, so it will take B too, still on any network.
        assertEquals(listOf(WorkInfo.State.ENQUEUED to NetworkType.CONNECTED), liveDrains())
    }

    @Test fun `a background start queues behind a running drain, never cancelling it`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo().queueDownloadsForPlaylist(pid)
        startDrain()
        member(pid, 1, track("B"))
        repo().queueDownloadsForPlaylist(pid, background = true)

        // The running tap drain goes on; B, which its snapshot missed, waits behind it for Wi-Fi.
        assertEquals(
            setOf(WorkInfo.State.RUNNING to NetworkType.CONNECTED, WorkInfo.State.BLOCKED to NetworkType.UNMETERED),
            liveDrains().toSet(),
        )
    }

    // ── Nothing strands a waiting download (#474) ─────────────────────────

    @Test fun `turning Stash Mixes off never cancels a download run`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        val repo = repo()
        repo.queueDownloadsForPlaylist(pid)
        repo.applyStashMixesEnabled(false)
        assertEquals(listOf(WorkInfo.State.ENQUEUED to NetworkType.CONNECTED), liveDrains())
    }

    @Test fun `a sync with nothing new still restarts a drain for songs already waiting`() = runTest {
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = db.trackDao().insert(track("Left waiting"))))
        assertEquals(0, repo().queueKeptPlaylists())
        assertEquals(listOf(WorkInfo.State.ENQUEUED to NetworkType.UNMETERED), liveDrains())
    }

    /** The worker shows its notification before it looks at the queue: no work, no start. */
    @Test fun `with nothing waiting, nothing starts a drain`() = runTest {
        member(playlist("Kept", keepOffline = true), 0, track("Done").copy(isDownloaded = true, filePath = "/x.flac"))
        val repo = repo()
        assertEquals(0, repo.queueKeptPlaylists())
        repo.resumeWaitingDownloads()
        assertEquals(emptyList<WorkInfo>(), drains())
    }

    /** Failed songs retry when a drain runs for real work, never on their own (relay quota, notification). */
    @Test fun `with only failed songs queued, neither a cold start nor a sync starts a drain`() = runTest {
        val failed = db.trackDao().insert(track("Unmatchable"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = failed, status = DownloadStatus.FAILED, retryCount = 1))
        val repo = repo()
        repo.resumeWaitingDownloads()
        assertEquals(0, repo.queueKeptPlaylists())
        assertEquals(emptyList<WorkInfo>(), drains())
    }

    // ── The network rule is read fresh (#474) ─────────────────────────────

    @Test fun `turning Wi-Fi only off replaces a run still waiting for Wi-Fi`() = runTest {
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = db.trackDao().insert(track("Left waiting"))))
        repo(wifiOnly = true).resumeWaitingDownloads()
        repo(wifiOnly = false).resumeWaitingDownloads()
        // Nothing was running, so nothing was cancelled: the waiting run just stops waiting for Wi-Fi.
        assertEquals(listOf(WorkInfo.State.ENQUEUED to NetworkType.CONNECTED), liveDrains())
    }

    @Test fun `a run in progress is never replaced, even after Wi-Fi only is turned off`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        repo().queueDownloadsForPlaylist(pid)
        startDrain()
        member(pid, 1, track("B"))
        repo(wifiOnly = true).queueDownloadsForPlaylist(pid, background = true)
        member(pid, 2, track("C"))
        repo(wifiOnly = false).queueDownloadsForPlaylist(pid, background = true)

        // Replacing would cancel the song downloading now; the queued-behind run takes C.
        assertEquals(
            setOf(WorkInfo.State.RUNNING to NetworkType.CONNECTED, WorkInfo.State.BLOCKED to NetworkType.UNMETERED),
            liveDrains().toSet(),
        )
    }

    @Test fun `a manual sync downloads kept playlists on any network, even with Wi-Fi only on`() = runTest {
        member(playlist("Kept", keepOffline = true), 0, track("A"))
        assertEquals(1, repo(wifiOnly = true).queueKeptPlaylists(manualSync = true))
        assertEquals(listOf(WorkInfo.State.ENQUEUED to NetworkType.CONNECTED), liveDrains())
    }

    /** A cold start and a sync can both look for a waiting run at once; only one may start one. */
    @Test fun `two background starts at the same moment start one run`() = runTest {
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = db.trackDao().insert(track("Left waiting"))))
        val coldStart = repo()
        val sync = repo()
        listOf(launch { coldStart.resumeWaitingDownloads() }, launch { sync.resumeWaitingDownloads() }).joinAll()
        assertEquals(listOf(WorkInfo.State.ENQUEUED to NetworkType.UNMETERED), liveDrains())
    }

    @Test fun `resuming starts one background drain, and never stacks a second one`() = runTest {
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = db.trackDao().insert(track("Left waiting"))))
        val repo = repo()
        repo.resumeWaitingDownloads()
        repo.resumeWaitingDownloads() // a second cold start while still off Wi-Fi
        assertEquals(listOf(WorkInfo.State.ENQUEUED to NetworkType.UNMETERED), liveDrains())
    }

    /**
     * A song an earlier Download-mode sync queued is stuck in Stream-only mode: that
     * mode never runs the sync's download step, and the row counts as handled. Queueing
     * the playlist hands it to the drain; another playlist's stuck song is left alone.
     */
    @Test fun `stream-only mode hands this playlist's stuck sync rows to the drain`() = runTest {
        val (stuck, failedOnce, otherPlaylists) = seedStuckSyncRows()

        assertEquals(2, repo(streamOnly = true).queueDownloadsForPlaylist(playlist("Kept")))

        val drainable = db.downloadQueueDao().pendingDiscoveryDownloads().map { it.trackId }
        assertEquals(setOf(stuck, failedOnce), drainable.toSet())
        assertEquals(syncId, db.downloadQueueDao().getByTrackId(otherPlaylists)!!.syncId)
        assertEquals(1, drains().size)
    }

    @Test fun `download mode leaves sync rows to the sync`() = runTest {
        seedStuckSyncRows()
        assertEquals(0, repo(streamOnly = false).queueDownloadsForPlaylist(playlist("Kept")))
        assertEquals(emptyList<Long>(), db.downloadQueueDao().pendingDiscoveryDownloads().map { it.trackId })
    }

    private var syncId = 0L

    /** Sync rows for songs of the playlist named "Kept" (created first here) and of another playlist. */
    private suspend fun seedStuckSyncRows(): Triple<Long, Long, Long> {
        syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        val kept = playlist("Kept")
        val stuck = member(kept, 0, track("Stuck"))
        val failedOnce = member(kept, 1, track("Failed once"))
        val otherPlaylists = member(playlist("Other"), 0, track("Other"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = stuck, syncId = syncId))
        db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = failedOnce, syncId = syncId, status = DownloadStatus.FAILED, retryCount = 1),
        )
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = otherPlaylists, syncId = syncId))
        return Triple(stuck, failedOnce, otherPlaylists)
    }

    // ── No tap cancels a song in progress (#474) ──────────────────────────

    @Test fun `a tap while a song downloads queues behind it, and never stacks another`() = runTest {
        val pid = playlist("Mix")
        member(pid, 0, track("A"))
        val repo = repo()
        repo.queueDownloadsForPlaylist(pid)
        startDrain() // A is downloading
        member(pid, 1, track("B"))
        repo.queueDownloadsForPlaylist(pid) // queues behind A's run, cancelling nothing
        member(pid, 2, track("C"))
        repo.queueDownloadsForPlaylist(pid) // the run waiting behind takes C when it starts

        assertEquals(
            listOf(WorkInfo.State.RUNNING to NetworkType.CONNECTED, WorkInfo.State.BLOCKED to NetworkType.CONNECTED),
            liveDrains().sortedBy { it.first },
        )
    }

    // ── A tap is the user's own request (#474) ────────────────────────────

    @Test fun `a song the user tapped keeps its row when its playlist's switch goes off`() = runTest {
        val pid = playlist("Mix")
        val tapped = member(pid, 0, track("Tapped"))
        val retried = member(pid, 1, track("Tapped again after failing"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = retried, status = DownloadStatus.FAILED, retryCount = 3))
        val repo = repo()
        assertEquals(true, repo.queueDownload(tapped)) // a new row
        assertEquals(true, repo.queueDownload(retried)) // the failed row, reused

        repo.setPlaylistDownload(pid, on = true)
        repo.setPlaylistDownload(pid, on = false)

        val waiting = db.downloadQueueDao().pendingDiscoveryDownloads().map { it.trackId }
        assertEquals(setOf(tapped, retried), waiting.toSet())
    }

    private fun drains(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(DiscoveryDownloadWorker.UNIQUE_WORK_NAME).get()

    private fun liveDrains() = drains().filter { !it.state.isFinished }.map { it.state to it.constraints.requiredNetworkType }

    /** Runs the waiting drain's stand-in worker (see setUp), which holds it RUNNING. */
    private fun startDrain() {
        val id = drains().single { it.state == WorkInfo.State.ENQUEUED }.id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        assertEquals(WorkInfo.State.RUNNING, drains().single { it.id == id }.state)
    }

    private suspend fun playlist(name: String, keepOffline: Boolean = false): Long =
        db.playlistDao().findBySourceId("custom_$name")?.id ?: db.playlistDao().insert(
            PlaylistEntity(
                name = name, source = MusicSource.BOTH, sourceId = "custom_$name", type = PlaylistType.CUSTOM,
                keepOffline = keepOffline,
            ),
        )

    private suspend fun track(title: String) = TrackEntity(
        title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
        canonicalTitle = title.lowercase(), canonicalArtist = "a",
    )

    private suspend fun member(playlistId: Long, position: Int, track: TrackEntity): Long {
        val id = db.trackDao().insert(track)
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = id, position = position))
        return id
    }

    private fun repo(streamOnly: Boolean = false, wifiOnly: Boolean = true): MusicRepositoryImpl {
        // "Run recommendations when" at its default: it must never reach a download.
        val network = mockk<DownloadNetworkPreference>()
        coEvery { network.current() } returns DownloadNetworkMode.WIFI_AND_CHARGING
        val streaming = mockk<StreamingPreference>()
        coEvery { streaming.current() } returns streamOnly
        val sync = mockk<SyncPreferencesManager>(relaxed = true)
        // Like DataStore, reading the setting suspends: that is where two starts can interleave.
        every { sync.preferences } returns flow { yield(); emit(SyncPreferences(wifiOnly = wifiOnly)) }
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
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = sync,
            singleTrackDownloadEnqueuer = mockk(relaxed = true),
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
        )
    }
}
