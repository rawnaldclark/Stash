package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.DownloadQueueState
import com.stash.core.data.sync.classifier.DownloadFailureClassifier
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * MockK tests for [DiscoveryDownloadWorker] — the v0.9.20 worker that
 * drains discovery_queue PENDING/retryable-FAILED rows (sync_id IS NULL)
 * via the existing [TrackDownloader] abstraction. Mirrors the
 * [com.stash.data.download.lossless.LosslessRetryWorkerTest] MockK pattern.
 */
class DiscoveryDownloadWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val trackDownloader: TrackDownloader = mockk()
    private val audioDurationExtractor: AudioDurationExtractor = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlocked(any(), any(), any(), any()) } returns false
    }
    private val syncNotificationManager: SyncNotificationManager = mockk(relaxed = true)
    private val downloadJobs = DownloadJobRegistry()
    private val queueState: DownloadQueueState = mockk(relaxed = true) {
        coEvery { isPaused() } returns false
    }
    private val resumer: DownloadQueueResumer = mockk(relaxed = true)

    /**
     * The worker now gates every row on an atomic claim and reads the row
     * status back to settle cancellation races. A relaxed mock answers 0 /
     * null to both, which reads as "another worker took this row" and
     * "cancelled" — so without these stubs the worker correctly skips every
     * item and no behavioural assertion below can fire. Default = the happy
     * path: this worker owns the row and the row is not cancelled.
     */
    @Before fun stubClaimAndStatus() {
        coEvery { downloadQueueDao.claimForDownload(any()) } returns 1
        coEvery { downloadQueueDao.completeIfInProgress(any(), any()) } returns 1
        coEvery { downloadQueueDao.getStatusById(any()) } returns DownloadStatus.COMPLETED
    }

    private fun newWorker() = DiscoveryDownloadWorker(
        appContext, workerParams,
        downloadQueueDao, trackDao, trackDownloader,
        audioDurationExtractor, blocklistGuard, syncNotificationManager, downloadJobs,
        fileReplacement = mockk(relaxed = true),
        classifier = DownloadFailureClassifier(),
        queueState = queueState,
        resumer = resumer,
    )

    @Test fun `empty queue returns success and does not invoke TrackDownloader`() = runTest {
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns emptyList()

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    // ── Worker-loop convergence: chainRefresh must only fire on real progress ──
    //
    // Regression coverage for the runaway 3-worker cycle (refresh→discovery→
    // download→chainRefresh→…). An idle/no-op DiscoveryDownloadWorker run must
    // TERMINATE the chain — only a run that produced new playable content
    // (a completed download) is allowed to kick the mix refresh.
    //
    // chainRefresh is `internal` (was `private`) purely so spyk can intercept it
    // here; its body/behaviour is otherwise unchanged.

    @Test fun `empty queue does not chain a mix refresh`() = runTest {
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns emptyList()
        val worker = spyk(newWorker())
        every { worker.chainRefresh() } returns Unit

        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify(exactly = 0) { worker.chainRefresh() }
    }

    @Test fun `drain that completes nothing does not chain`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed(
            error = "yt-dlp timeout",
        )
        val worker = spyk(newWorker())
        every { worker.chainRefresh() } returns Unit

        worker.doWork()

        verify(exactly = 0) { worker.chainRefresh() }
    }

    @Test fun `drain with a successful download chains a refresh`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Success(
            filePath = "/tmp/file.flac",
        )
        coEvery { audioDurationExtractor.extract(any()) } returns null
        val worker = spyk(newWorker())
        every { worker.chainRefresh() } returns Unit

        worker.doWork()

        verify(exactly = 1) { worker.chainRefresh() }
    }

    @Test fun `drain that only completes an already-downloaded shortcut chains a refresh`() = runTest {
        // The already-downloaded→COMPLETED shortcut produces a newly-COMPLETED
        // queue row (new playable membership becomes linkable), so it counts as
        // progress and SHOULD chain.
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L, isDownloaded = true, filePath = "/already/here.flac")
        val worker = spyk(newWorker())
        every { worker.chainRefresh() } returns Unit

        worker.doWork()

        verify(exactly = 1) { worker.chainRefresh() }
    }

    @Test fun `successful download marks COMPLETED and writes isDownloaded`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Success(
            filePath = "/tmp/file.flac",
        )
        coEvery { audioDurationExtractor.extract(any()) } returns null

        newWorker().doWork()

        coVerify(exactly = 1) {
            trackDao.markAsDownloaded(
                trackId = 1L,
                filePath = "/tmp/file.flac",
                fileSizeBytes = any(),
                // downloadedAt has a default of System.currentTimeMillis() at call time;
                // MockK resolves defaults at verification time, so an explicit any()
                // matcher avoids a sub-millisecond timestamp mismatch.
                downloadedAt = any(),
                sampleRateHz = null,
                bitsPerSample = null,
            )
        }
        coVerify(exactly = 1) {
            downloadQueueDao.completeIfInProgress(id = 100L, completedAt = any())
        }
    }

    @Test fun `unmatched outcome marks FAILED with NO_MATCH and increments retry count`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Unmatched(
            rejectedVideoId = "abc123",
        )

        newWorker().doWork()

        coVerify(exactly = 1) { downloadQueueDao.incrementRetryCount(100L) }
        coVerify(exactly = 1) {
            downloadQueueDao.failIfInProgress(
                id = 100L,
                errorMessage = any(),
                failureType = DownloadFailureType.NO_MATCH,
                completedAt = any(),
                rejectedVideoId = "abc123",
            )
        }
    }

    @Test fun `a network failure goes back to the queue with a retry used and the drain retries`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed(
            error = "yt-dlp timeout",
        )

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 1) {
            downloadQueueDao.revertToPendingAfterInterruption(100L, match { it!!.contains("yt-dlp timeout") }, DownloadFailureType.NETWORK)
        }
        coVerify(exactly = 0) { downloadQueueDao.failIfInProgress(any(), any(), any(), any(), any()) }
    }

    @Test fun `a thrown network error is classified by its cause`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } throws
            java.io.IOException("download stalled", java.net.UnknownHostException("youtube.com"))

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 1) { downloadQueueDao.revertToPendingAfterInterruption(100L, any(), DownloadFailureType.NETWORK) }
    }

    @Test fun `the last attempt stops asking WorkManager to retry`() = runTest {
        every { workerParams.runAttemptCount } returns DiscoveryDownloadWorker.MAX_ATTEMPTS - 1
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("yt-dlp timeout")

        assertEquals(androidx.work.ListenableWorker.Result.success(), newWorker().doWork())
    }

    @Test fun `a source failure is FAILED with its type`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed(
            error = "ERROR: [youtube] abc: Video unavailable",
        )

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { downloadQueueDao.incrementRetryCount(100L) }
        coVerify(exactly = 1) {
            downloadQueueDao.failIfInProgress(
                id = 100L,
                errorMessage = any(),
                failureType = DownloadFailureType.PROVIDER_UNAVAILABLE,
                completedAt = any(),
                rejectedVideoId = any(),
            )
        }
    }

    @Test fun `a network failure that keeps happening is FAILED`() = runTest {
        val entry = entry(id = 100L, trackId = 1L).copy(retryCount = DownloadFailureClassifier.TRANSIENT_RETRY_LIMIT)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("yt-dlp timeout")

        newWorker().doWork()

        coVerify(exactly = 1) {
            downloadQueueDao.failIfInProgress(
                id = 100L,
                errorMessage = any(),
                failureType = DownloadFailureType.NETWORK,
                completedAt = any(),
                rejectedVideoId = any(),
            )
        }
        coVerify(exactly = 0) { downloadQueueDao.revertToPendingAfterInterruption(any(), any(), any()) }
    }

    @Test fun `a full phone stops the drain, uses no retry and says so`() = runTest {
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 101L, trackId = 2L))
        coEvery { trackDao.getById(any()) } answers { stubTrack(firstArg()) }
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns
            TrackDownloadOutcome.Failed("ERROR: unable to write data: [Errno 28] No space left on device")

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { trackDownloader.downloadTrack(any(), any()) }
        coVerify(exactly = 1) { downloadQueueDao.revertToPendingKeepingRetries(100L, any(), DownloadFailureType.STORAGE_FULL) }
        coVerify(exactly = 0) { downloadQueueDao.incrementRetryCount(any()) }
        coVerify(exactly = 0) { downloadQueueDao.claimForDownload(101L) }
        verify(exactly = 1) { queueState.setStorageFull(true) }
        verify(exactly = 1) { syncNotificationManager.showStorageFull() }
    }

    @Test fun `a rate limit stops the drain and says when it tries again`() = runTest {
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 101L, trackId = 2L))
        coEvery { trackDao.getById(any()) } answers { stubTrack(firstArg()) }
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns
            TrackDownloadOutcome.Failed("ERROR: [youtube] abc: HTTP Error 429: Too Many Requests")

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { trackDownloader.downloadTrack(any(), any()) }
        coVerify(exactly = 1) { downloadQueueDao.revertToPendingAfterInterruption(100L, any(), DownloadFailureType.RATE_LIMITED) }
        coVerify(exactly = 0) { downloadQueueDao.claimForDownload(101L) }
        verify(exactly = 1) { syncNotificationManager.showRateLimited(any()) }
    }

    @Test fun `a paused queue downloads nothing`() = runTest {
        coEvery { queueState.isPaused() } returns true
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry(id = 100L, trackId = 1L))

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { downloadQueueDao.pendingDiscoveryDownloads() }
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `deferred outcome does NOT touch the queue row`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Deferred

        newWorker().doWork()

        coVerify(exactly = 0) { downloadQueueDao.failIfInProgress(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { downloadQueueDao.completeIfInProgress(any(), any()) }
        coVerify(exactly = 0) { downloadQueueDao.incrementRetryCount(any()) }
    }

    @Test fun `blocked track deletes the queue row and does NOT invoke TrackDownloader`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns true

        newWorker().doWork()

        coVerify(exactly = 1) { downloadQueueDao.deleteByTrackId(1L) }
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `already-downloaded track is idempotently marked COMPLETED without re-downloading`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L, isDownloaded = true, filePath = "/already/here.flac")

        newWorker().doWork()

        coVerify(exactly = 1) {
            downloadQueueDao.completeIfInProgress(id = 100L, completedAt = any())
        }
        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `row is claimed IN_PROGRESS BEFORE invoking TrackDownloader`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("fail")

        newWorker().doWork()

        // The order matters — the IN_PROGRESS claim must land before the
        // downloader runs. coVerifyOrder enforces sequence; plain coVerify
        // only checks existence.
        coVerifyOrder {
            downloadQueueDao.claimForDownload(100L)
            trackDownloader.downloadTrack(any(), any())
        }
    }

    @Test fun `a row claimed by another worker is skipped entirely`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { downloadQueueDao.claimForDownload(100L) } returns 0

        newWorker().doWork()

        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
    }

    @Test fun `a cancelled row is not downloaded even after a successful claim`() = runTest {
        val entry = entry(id = 100L, trackId = 1L)
        coEvery { downloadQueueDao.pendingDiscoveryDownloads() } returns listOf(entry)
        coEvery { trackDao.getById(1L) } returns stubTrack(1L)
        coEvery { downloadQueueDao.getStatusById(100L) } returns DownloadStatus.SKIPPED

        newWorker().doWork()

        coVerify(exactly = 0) { trackDownloader.downloadTrack(any(), any()) }
        coVerify(exactly = 0) { downloadQueueDao.completeIfInProgress(any(), any()) }
    }

    private fun entry(id: Long, trackId: Long) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.PENDING,
        syncId = null,
        searchQuery = "artist - title",
    )

    private fun stubTrack(
        id: Long,
        isDownloaded: Boolean = false,
        filePath: String? = null,
    ) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = isDownloaded,
        filePath = filePath,
    )
}
