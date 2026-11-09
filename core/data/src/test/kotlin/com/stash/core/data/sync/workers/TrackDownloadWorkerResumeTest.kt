package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.files.TrackFileReplacement
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.DownloadQueueState
import com.stash.core.data.sync.DownloadSlots
import com.stash.core.data.sync.ResumeTrigger
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.classifier.DownloadFailureClassifier
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * TrackDownloadWorker's resume mode, the songs a sync queued downloading outside a sync, with a
 * real database, job registry, classifier and worker:
 * - it takes a sync's PENDING rows with no sync id in its input, and nothing in Online mode or
 *   while paused;
 * - a network failure puts the row back with one retry used and asks WorkManager to retry;
 * - a full phone stops the batch after the first failure, the rest PENDING with no retry used;
 * - a rate limit stops it starting songs and schedules the next try;
 * - a row a live job holds is left to it, while a claim nobody holds is taken back and downloaded.
 *
 * And the single retry (Failed downloads' Retry) on the same taxonomy: a network blip waits and
 * retries instead of failing at once, without using one of the row's automatic retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDownloadWorkerResumeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private var syncId = 0L
    private val slots = DownloadSlots()
    private val jobs = DownloadJobRegistry()
    private val trackDownloader = mockk<TrackDownloader>()
    private val storageFull = MutableStateFlow(false)
    private var paused = false
    private var online = false
    private val queueState = mockk<DownloadQueueState>(relaxed = true).also {
        coEvery { it.isPaused() } answers { paused }
        io.mockk.every { it.storageFull } returns storageFull
    }
    private val streamingPreference = mockk<StreamingPreference>(relaxed = true).also {
        coEvery { it.current() } answers { online }
    }
    private val resumer = mockk<DownloadQueueResumer>(relaxed = true).also {
        coEvery { it.isSyncDownloadRunning() } returns false
    }
    private val notifications = mockk<SyncNotificationManager>(relaxed = true)

    @Before fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
    }

    @After fun tearDown() { db.close() }

    @Test fun `runs a sync's pending songs with no sync id`() = runBlocking {
        val songs = listOf(queueSyncSong(1), queueSyncSong(2))
        val file = tmp.newFile("song.opus").apply { writeBytes(ByteArray(20_000)) }
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Success(file.absolutePath)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        songs.forEach { assertEquals(DownloadStatus.COMPLETED, db.downloadQueueDao().getStatusById(it)) }
        coVerify(exactly = 2) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `does nothing in Online mode`() = runBlocking {
        online = true
        val song = queueSyncSong(1)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(song))
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `does nothing while paused`() = runBlocking {
        paused = true
        val song = queueSyncSong(1)

        worker().doWork()

        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(song))
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `leaves the queue to a sync that is downloading`() = runBlocking {
        coEvery { resumer.isSyncDownloadRunning() } returns true
        val song = queueSyncSong(1)

        worker().doWork()

        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(song))
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `a network failure leaves the song pending with one retry used and asks to retry`() = runBlocking {
        val song = queueSyncSong(1)
        coEvery { trackDownloader.downloadTrack(any(), any()) } throws
            java.io.IOException("download stalled", java.net.SocketTimeoutException("read timed out"))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val row = db.downloadQueueDao().getById(song)!!
        assertEquals(DownloadStatus.PENDING, row.status)
        assertEquals(1, row.retryCount)
        assertEquals(DownloadFailureType.NETWORK, row.failureType)
    }

    @Test fun `the last attempt stops asking to retry`() = runBlocking {
        val song = queueSyncSong(1)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("Connection reset by peer")

        val result = worker(attempt = TrackDownloadWorker.MAX_ATTEMPTS - 1).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(song))
    }

    @Test fun `a full phone stops after the first failure and uses no retries`() = runBlocking {
        val songs = (1..3).map { queueSyncSong(it) }
        // One song at a time, so the first failure lands before the next song starts.
        repeat(DownloadSlots.COUNT - 1) { check(slots.permits.tryAcquire()) }
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns
            TrackDownloadOutcome.Failed("ERROR: unable to write data: [Errno 28] No space left on device")

        val result = try {
            worker().doWork()
        } finally {
            repeat(DownloadSlots.COUNT - 1) { slots.permits.release() }
        }

        assertEquals("no retry: nothing helps until there is room", ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { trackDownloader.downloadTrack(any(), any()) }
        val rows = songs.map { db.downloadQueueDao().getById(it)!! }
        rows.forEach {
            assertEquals(DownloadStatus.PENDING, it.status)
            assertEquals(0, it.retryCount)
        }
        assertEquals(1, rows.count { it.failureType == DownloadFailureType.STORAGE_FULL })
        verify(exactly = 1) { queueState.setStorageFull(true) }
        verify(exactly = 1) { notifications.showStorageFull() }
    }

    @Test fun `a rate limit stops starting songs and schedules the next try`() = runBlocking {
        val songs = (1..3).map { queueSyncSong(it) }
        repeat(DownloadSlots.COUNT - 1) { check(slots.permits.tryAcquire()) }
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns
            TrackDownloadOutcome.Failed("ERROR: [youtube] abc: HTTP Error 429: Too Many Requests")

        val result = try {
            worker().doWork()
        } finally {
            repeat(DownloadSlots.COUNT - 1) { slots.permits.release() }
        }

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { trackDownloader.downloadTrack(any(), any()) }
        val rows = songs.map { db.downloadQueueDao().getById(it)!! }
        rows.forEach { assertEquals(DownloadStatus.PENDING, it.status) }
        assertEquals("only the song that hit the limit used a retry", listOf(1, 0, 0), rows.map { it.retryCount }.sortedDescending())
        coVerify(exactly = 1) { resumer.resume(ResumeTrigger.AUTO, DownloadQueueResumer.RATE_LIMIT_DELAY) }
        verify(exactly = 1) { notifications.showRateLimited(any()) }
    }

    @Test fun `a song a live job holds is left alone, a claim nobody holds is taken back`() = runBlocking {
        val held = queueSyncSong(1)
        val abandoned = queueSyncSong(2)
        val liveJob = Job()
        check(jobs.claimAndRegister(held, liveJob) { db.downloadQueueDao().claimForDownload(held) != 0 })
        check(db.downloadQueueDao().claimForDownload(abandoned) != 0) // a killed process's claim
        val file = tmp.newFile("song.opus").apply { writeBytes(ByteArray(20_000)) }
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Success(file.absolutePath)

        try {
            worker().doWork()
        } finally {
            liveJob.cancel()
        }

        assertEquals(DownloadStatus.IN_PROGRESS, db.downloadQueueDao().getStatusById(held))
        assertEquals(DownloadStatus.COMPLETED, db.downloadQueueDao().getStatusById(abandoned))
        coVerify(exactly = 1) { trackDownloader.downloadTrack(any(), any()) }
    }

    // ── The single retry ────────────────────────────────────────────────

    @Test fun `single retry - a network failure waits and retries, using none of the row's retries`() = runBlocking {
        val song = queueFailedSong(retries = 2)
        check(db.downloadQueueDao().atomicallyClaimForRetry(song) == 1)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("Connection reset by peer")

        val result = worker(input = workDataOf(TrackDownloadWorker.KEY_QUEUE_ID to song)).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val row = db.downloadQueueDao().getById(song)!!
        assertEquals(DownloadStatus.PENDING, row.status)
        assertEquals(2, row.retryCount)
        assertEquals(DownloadFailureType.NETWORK, row.failureType)
    }

    @Test fun `single retry - out of attempts, the failure shows`() = runBlocking {
        val song = queueFailedSong()
        check(db.downloadQueueDao().atomicallyClaimForRetry(song) == 1)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("Connection reset by peer")

        val result = worker(input = workDataOf(TrackDownloadWorker.KEY_QUEUE_ID to song), attempt = TrackDownloadWorker.MAX_ATTEMPTS - 1).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val row = db.downloadQueueDao().getById(song)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(DownloadFailureType.NETWORK, row.failureType)
    }

    @Test fun `single retry - a source failure fails at once`() = runBlocking {
        val song = queueFailedSong()
        check(db.downloadQueueDao().atomicallyClaimForRetry(song) == 1)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("ERROR: [youtube] abc: Video unavailable")

        val result = worker(input = workDataOf(TrackDownloadWorker.KEY_QUEUE_ID to song)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val row = db.downloadQueueDao().getById(song)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(DownloadFailureType.PROVIDER_UNAVAILABLE, row.failureType)
    }

    @Test fun `single retry - a full phone holds the song and says so`() = runBlocking {
        val song = queueFailedSong(retries = 1)
        check(db.downloadQueueDao().atomicallyClaimForRetry(song) == 1)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("No space left on device")

        val result = worker(input = workDataOf(TrackDownloadWorker.KEY_QUEUE_ID to song)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val row = db.downloadQueueDao().getById(song)!!
        assertEquals(DownloadStatus.PENDING, row.status)
        assertEquals(1, row.retryCount)
        verify(exactly = 1) { queueState.setStorageFull(true) }
        verify(exactly = 1) { notifications.showStorageFull() }
    }

    /** A song whose download failed on a network error, for Retry. */
    private suspend fun queueFailedSong(retries: Int = 0): Long {
        val id = queueSyncSong(100 + retries)
        db.downloadQueueDao().claimForDownload(id)
        db.downloadQueueDao().failIfInProgress(id, "timeout", DownloadFailureType.NETWORK, completedAt = 1L)
        repeat(retries) { db.downloadQueueDao().incrementRetryCount(id) }
        return id
    }

    /** A song in a switched-on playlist, queued by a sync: the rows the resume run takes. */
    private suspend fun queueSyncSong(n: Int): Long {
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Song $n", artist = "Artist", durationMs = 1000L, source = MusicSource.BOTH,
                canonicalTitle = "song $n", canonicalArtist = "artist",
            ),
        )
        val playlist = db.playlistDao().insert(
            PlaylistEntity(
                name = "Playlist $n", source = MusicSource.BOTH, sourceId = "pl_$n", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            ),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlist, trackId = trackId, position = 0, addedAt = Instant.EPOCH))
        return db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = trackId, syncId = syncId, searchQuery = "artist - song $n"),
        )
    }

    private fun worker(
        attempt: Int = 0,
        input: androidx.work.Data = workDataOf(TrackDownloadWorker.KEY_RESUME to true),
    ): TrackDownloadWorker =
        TestListenableWorkerBuilder<TrackDownloadWorker>(context)
            .setInputData(input)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = TrackDownloadWorker(
                    appContext, workerParameters,
                    downloadQueueDao = db.downloadQueueDao(),
                    trackDao = db.trackDao(),
                    syncHistoryDao = mockk(relaxed = true),
                    syncStateManager = mockk(relaxed = true),
                    syncNotificationManager = notifications,
                    trackDownloader = trackDownloader,
                    tokenManager = mockk(relaxed = true),
                    audioDurationExtractor = mockk(relaxed = true),
                    blocklistGuard = mockk(relaxed = true),
                    streamingPreference = streamingPreference,
                    classifier = DownloadFailureClassifier(),
                    reconciliationUseCase = mockk(relaxed = true),
                    fileExistenceSessionFactory = mockk(relaxed = true),
                    fileAdopter = mockk(relaxed = true),
                    syncLog = mockk(relaxed = true),
                    flacUpgradeSweeper = mockk(relaxed = true),
                    downloadJobs = jobs,
                    fileReplacement = TrackFileReplacement(db.trackDao(), LocalFileOps(appContext)),
                    queueState = queueState,
                    resumer = resumer,
                    singleTrackDownloadEnqueuer = mockk(relaxed = true),
                    downloadSlots = slots,
                )
            })
            .build()
}
