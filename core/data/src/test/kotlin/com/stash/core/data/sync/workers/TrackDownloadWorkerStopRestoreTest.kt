package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.files.TrackFileReplacement
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.DownloadQueueState
import com.stash.core.data.sync.ResumeTrigger
import com.stash.core.data.sync.WaitingHold
import com.stash.core.data.sync.classifier.DownloadFailureClassifier
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A track the YouTube-library backfill handed back for a better copy
 * (markForReplacement: not downloaded, file kept) got its kept file back as
 * its download on FAILED, on Deferred and on a cancel after the commit, but
 * not when the download was stopped before it returned anything: the song
 * dropped out of the Library with its file unseen on disk. Every stop now
 * restores it, in both of TrackDownloadWorker's modes. A real Room database
 * and real files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDownloadWorkerStopRestoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val trackDownloader = mockk<TrackDownloader>()
    private val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true)
    private val syncStateManager = mockk<SyncStateManager>(relaxed = true)
    private val resumer = mockk<DownloadQueueResumer>(relaxed = true)
    private var paused = false
    private val queueState = mockk<DownloadQueueState>(relaxed = true).also {
        coEvery { it.isPaused() } answers { paused }
    }
    private lateinit var keptFile: File

    @Before fun setUp() {
        // The workers build their notification's Cancel action through WorkManager.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keptFile = tmp.newFile("lacrymosa.opus").apply { writeBytes(ByteArray(20_000)) }
    }

    @After fun tearDown() { db.close() }

    // ── Single-track mode ────────────────────────────────────────────────

    @Test fun `single track - a replacement stopped mid-download keeps its file`() = runBlocking {
        val (trackId, queueId) = trackHandedBackForReplacement()
        // WorkManager stops the work while the download runs.
        coEvery { trackDownloader.downloadTrack(any(), any()) } throws CancellationException("stopped")

        try {
            singleTrackWorker(queueId).doWork()
        } catch (expected: CancellationException) {
        }

        assertKeptFileIsTheDownload(trackId)
        assertEquals("the claim is handed back", DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(queueId))
    }

    @Test fun `single track - a replacement the user cancels mid-download keeps its file`() = runBlocking {
        val (trackId, queueId) = trackHandedBackForReplacement()
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            db.downloadQueueDao().markCancelledIfActive(queueId, System.currentTimeMillis())
            throw CancellationException("cancelled")
        }

        val result = singleTrackWorker(queueId).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertKeptFileIsTheDownload(trackId)
    }

    @Test fun `single track - a first download stopped mid-way restores nothing`() = runBlocking {
        val trackId = db.trackDao().insert(song())
        val queueId = db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, userRequested = true))
        coEvery { trackDownloader.downloadTrack(any(), any()) } throws CancellationException("stopped")

        try {
            singleTrackWorker(queueId).doWork()
        } catch (expected: CancellationException) {
        }

        assertFalse(db.trackDao().getById(trackId)!!.isDownloaded)
    }

    // ── Chain mode ───────────────────────────────────────────────────────

    @Test fun `chain - a replacement the user cancels mid-download keeps its file`() = runBlocking {
        val (trackId, queueId) = trackHandedBackForReplacement()
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            db.downloadQueueDao().markCancelledIfActive(queueId, System.currentTimeMillis())
            throw CancellationException("cancelled")
        }

        chainWorker().doWork()

        assertKeptFileIsTheDownload(trackId)
    }

    @Test fun `chain - a replacement stopped mid-download keeps its file`() = runBlocking {
        val (trackId, queueId) = trackHandedBackForReplacement()
        // Not the user: the worker stopped.
        coEvery { trackDownloader.downloadTrack(any(), any()) } throws CancellationException("stopped")

        chainWorker().doWork()

        assertKeptFileIsTheDownload(trackId)
        // The claim is handed back as it stops: left IN_PROGRESS, the song showed as
        // downloading until a reset, and no later run would take it.
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(queueId))
    }

    @Test fun `chain - a stop by a lost connection hands the claims back and leaves the sync open`() = runBlocking {
        val queueId = queueFirstDownload()
        val run = stopChainMidDownload(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)

        assertTrue(run.exceptionOrNull() is CancellationException)
        assertEquals("waiting for the next run, not downloading", DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(queueId))
        // WorkManager reruns the step: the sync isn't over, so it isn't "Cancelled".
        coVerify(exactly = 0) { syncHistoryDao.updateStatus(any(), SyncState.CANCELLED, any(), any()) }
        verify(exactly = 1) { syncStateManager.onWaitingForNetwork() }
        verify(exactly = 0) { syncStateManager.reset() }
    }

    @Test fun `chain - the app's own stop hands the claims back and cancels the sync`() = runBlocking {
        val queueId = queueFirstDownload()
        val run = stopChainMidDownload(WorkInfo.STOP_REASON_CANCELLED_BY_APP)

        assertTrue(run.exceptionOrNull() is CancellationException)
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(queueId))
        coVerify(exactly = 1) { syncHistoryDao.updateStatus(1L, SyncState.CANCELLED, any(), any()) }
        verify(exactly = 1) { syncStateManager.reset() }
        verify(exactly = 0) { syncStateManager.onWaitingForNetwork() }
    }

    @Test fun `chain - a stop for the job's time limit says it continues shortly, not that it waits for a network`() = runBlocking {
        val queueId = queueFirstDownload()
        val run = stopChainMidDownload(WorkInfo.STOP_REASON_TIMEOUT)

        assertTrue(run.exceptionOrNull() is CancellationException)
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(queueId))
        coVerify(exactly = 0) { syncHistoryDao.updateStatus(any(), SyncState.CANCELLED, any(), any()) }
        verify(exactly = 1) { syncStateManager.onContinuingShortly() }
        verify(exactly = 0) { syncStateManager.onWaitingForNetwork() }
    }

    @Test fun `only a lost connection, or no reason below Android 12, reads as waiting for a network`() {
        assertTrue(TrackDownloadWorker.stopWaitsForNetwork(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, sdkInt = 33))
        assertTrue(TrackDownloadWorker.stopWaitsForNetwork(WorkInfo.STOP_REASON_UNKNOWN, sdkInt = 30))
        assertFalse(TrackDownloadWorker.stopWaitsForNetwork(WorkInfo.STOP_REASON_UNKNOWN, sdkInt = 33))
        assertFalse(TrackDownloadWorker.stopWaitsForNetwork(WorkInfo.STOP_REASON_TIMEOUT, sdkInt = 33))
        assertFalse(TrackDownloadWorker.stopWaitsForNetwork(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT, sdkInt = 35))
    }

    @Test fun `chain - a song a network blip left waiting is downloaded after the sync`() = runBlocking {
        val queueId = queueFirstDownload()
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("Connection reset by peer")

        val result = chainWorker().doWork()

        assertEquals(1, result.outputData.getInt(TrackDownloadWorker.KEY_WAITING, -1))
        assertEquals(WaitingHold.NONE.name, result.outputData.getString(TrackDownloadWorker.KEY_WAITING_HOLD))
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(queueId))
        coVerify(exactly = 1) { resumer.resume(ResumeTrigger.AUTO, DownloadQueueResumer.AFTER_SYNC_DELAY) }
    }

    @Test fun `chain - songs queued while it ran (Retry all) are downloaded after it`() = runBlocking {
        queueFirstDownload()
        val landed = tmp.newFile("landed.opus").apply { writeBytes(ByteArray(20_000)) }
        var retried = 0L
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            // Retry all during the sync: a song back in the queue that this run's list doesn't have.
            if (retried == 0L) retried = queueFirstDownload()
            TrackDownloadOutcome.Success(landed.absolutePath)
        }

        val result = chainWorker().doWork()

        assertEquals("nothing of its own left", 0, result.outputData.getInt(TrackDownloadWorker.KEY_WAITING, -1))
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(retried))
        coVerify(exactly = 1) { resumer.resume(ResumeTrigger.AUTO, DownloadQueueResumer.AFTER_SYNC_DELAY) }
    }

    @Test fun `chain - with nothing left waiting, nothing is scheduled`() = runBlocking {
        queueFirstDownload()
        val landed = tmp.newFile("landed.opus").apply { writeBytes(ByteArray(20_000)) }
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Success(landed.absolutePath)

        chainWorker().doWork()

        coVerify(exactly = 0) { resumer.resume(any(), any(), any()) }
    }

    @Test fun `chain - paused, its songs wait and the summary is told they are paused`() = runBlocking {
        paused = true
        val queueId = queueFirstDownload()

        val result = chainWorker().doWork()

        assertEquals(1, result.outputData.getInt(TrackDownloadWorker.KEY_WAITING, -1))
        assertEquals(WaitingHold.PAUSED.name, result.outputData.getString(TrackDownloadWorker.KEY_WAITING_HOLD))
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(queueId))
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    /** A song downloading for the first time, queued for a sync. */
    private suspend fun queueFirstDownload(): Long {
        val trackId = db.trackDao().insert(song())
        return db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, userRequested = true))
    }

    /**
     * Runs the chain's download step until its song is downloading, then stops it the way
     * WorkManager does: the reason first, then the coroutine. The fake download waits on a
     * deferred this test holds, so nothing outlives the test.
     */
    private suspend fun stopChainMidDownload(stopReason: Int): Result<ListenableWorker.Result> = kotlinx.coroutines.coroutineScope {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            TrackDownloadOutcome.Deferred
        }
        val worker = chainWorker()
        val run = async(Dispatchers.Default) { worker.doWork() }
        try {
            withTimeout(30_000) { started.await() }
            @Suppress("RestrictedApi")
            worker.stop(stopReason)
            run.cancel()
            runCatching { withTimeout(30_000) { run.await() } }
        } finally {
            gate.complete(Unit)
            run.cancel()
        }
    }

    private suspend fun assertKeptFileIsTheDownload(trackId: Long) {
        val row = db.trackDao().getById(trackId)!!
        assertTrue("back in the Library on its kept file", row.isDownloaded)
        assertEquals(keptFile.absolutePath, row.filePath)
        assertTrue(keptFile.exists())
    }

    /** A downloaded track the backfill handed back for a better copy, and its queue row. */
    private suspend fun trackHandedBackForReplacement(): Pair<Long, Long> {
        val trackId = db.trackDao().insert(song(filePath = keptFile.absolutePath, isDownloaded = true))
        db.trackDao().markForReplacement(trackId)
        val queueId = db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, userRequested = true))
        return trackId to queueId
    }

    /** BOTH: what chain mode asks for when no service is connected. */
    private fun song(filePath: String? = null, isDownloaded: Boolean = false) = TrackEntity(
        title = "Lacrymosa", artist = "Evanescence", durationMs = 1000L, source = MusicSource.BOTH,
        canonicalTitle = "lacrymosa", canonicalArtist = "evanescence",
        filePath = filePath, isDownloaded = isDownloaded,
    )

    private fun singleTrackWorker(queueId: Long) = buildWorker(workDataOf(TrackDownloadWorker.KEY_QUEUE_ID to queueId))

    private fun chainWorker() = buildWorker(workDataOf(DiffWorker.KEY_SYNC_ID to 1L))

    private fun buildWorker(input: androidx.work.Data): TrackDownloadWorker =
        TestListenableWorkerBuilder<TrackDownloadWorker>(context)
            .setInputData(input)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = TrackDownloadWorker(
                    appContext, workerParameters,
                    downloadQueueDao = db.downloadQueueDao(),
                    trackDao = db.trackDao(),
                    syncHistoryDao = syncHistoryDao,
                    syncStateManager = syncStateManager,
                    syncNotificationManager = mockk(relaxed = true),
                    trackDownloader = trackDownloader,
                    tokenManager = mockk(relaxed = true),
                    audioDurationExtractor = mockk(relaxed = true),
                    blocklistGuard = mockk(relaxed = true),
                    streamingPreference = mockk(relaxed = true),
                    classifier = DownloadFailureClassifier(),
                    reconciliationUseCase = mockk(relaxed = true),
                    fileExistenceSessionFactory = mockk(relaxed = true),
                    fileAdopter = mockk(relaxed = true),
                    syncLog = mockk(relaxed = true),
                    flacUpgradeSweeper = mockk(relaxed = true),
                    downloadJobs = DownloadJobRegistry(),
                    fileReplacement = TrackFileReplacement(db.trackDao(), LocalFileOps(appContext)),
                    queueState = queueState,
                    resumer = resumer,
                    singleTrackDownloadEnqueuer = mockk(relaxed = true),
                )
            })
            .build()
}
