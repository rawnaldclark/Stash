package com.stash.core.data.sync.workers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.stash.core.data.R
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.library.LibraryReconciliationUseCase
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.DownloadQueueState
import com.stash.core.data.sync.ResumeTrigger
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.connectedDownloadSources
import com.stash.core.data.sync.classifier.DownloadFailureClassifier
import com.stash.core.data.sync.classifier.DownloadFailureClassifier.FailureLane
import com.stash.core.data.sync.classifier.DownloadPhase
import com.stash.core.data.sync.classifier.FailureContext
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlin.time.Duration

/**
 * Downloads queued songs, in one of three modes:
 *
 * - **Chain mode** (input [DiffWorker.KEY_SYNC_ID]): the third worker in the sync chain. Reconciles
 *   the library, then downloads every song a sync wants, writing the sync's history row.
 * - **Resume mode** (input [KEY_RESUME], unique work [RESUME_WORK_NAME]): the same songs outside a
 *   sync, so a queue a sync left (a Cancel, a network blip, a 429, a full phone) carries on by
 *   itself instead of waiting for the next sync ([DownloadQueueResumer] starts it).
 * - **Single-track mode** (input [KEY_QUEUE_ID]): one row, for a Retry.
 *
 * Chain and resume mode share [downloadBatch]: 8 songs at once (DownloadSlots), one failure
 * taxonomy ([DownloadFailureClassifier.laneFor]), a batch that stops starting songs when the phone
 * is full or YouTube asks us to wait.
 *
 * Promotes itself to a foreground service with an ongoing progress notification when Android lets
 * it (see [safeSetForeground]).
 *
 * Each track is downloaded through the [TrackDownloader] abstraction which
 * delegates to the full yt-dlp pipeline (search, download, tag, organize).
 *
 * Chain mode outputs [KEY_SYNC_ID], [KEY_DOWNLOADED], [KEY_FAILED] and [KEY_WAITING] counts.
 */
@HiltWorker
class TrackDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val syncHistoryDao: SyncHistoryDao,
    private val syncStateManager: SyncStateManager,
    private val syncNotificationManager: SyncNotificationManager,
    private val trackDownloader: TrackDownloader,
    private val tokenManager: com.stash.core.auth.TokenManager,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    private val classifier: DownloadFailureClassifier,
    private val reconciliationUseCase: LibraryReconciliationUseCase,
    private val fileExistenceSessionFactory: com.stash.core.data.library.FileExistenceSessionFactory,
    private val fileAdopter: com.stash.core.data.library.FileAdopter,
    private val syncLog: com.stash.core.data.sync.SyncLog,
    private val flacUpgradeSweeper: com.stash.core.data.lossless.FlacUpgradeSweeper,
    private val downloadJobs: DownloadJobRegistry,
    private val fileReplacement: com.stash.core.data.files.TrackFileReplacement,
    /** The user's Pause and the out-of-space hold. */
    private val queueState: DownloadQueueState,
    /** Starts the resume run after a batch that left songs, and says whether the sync's own step runs. */
    private val resumer: DownloadQueueResumer,
    /** A single retry YouTube asked us to hold off, queued again for later. */
    private val singleTrackDownloadEnqueuer: SingleTrackDownloadEnqueuer,
    /** The songs downloading at once, shared with DownloadManager and the discovery drain. */
    private val downloadSlots: com.stash.core.data.sync.DownloadSlots = com.stash.core.data.sync.DownloadSlots(),
) : CoroutineWorker(appContext, params) {

    companion object {

        const val KEY_SYNC_ID = "sync_id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_FAILED = "failed"

        /** Chain mode's output: songs left queued (a transient failure, a stop), which download later on their own. */
        const val KEY_WAITING = "waiting"

        /**
         * Optional input-data key. When present and non-`-1L`, the worker
         * runs in single-track mode against exactly this `download_queue`
         * row instead of the full sync-chain pass. Used by
         * `SingleTrackDownloadEnqueuer` (Task 9.5) to power one-tap retries
         * from the Failed Downloads viewer.
         */
        const val KEY_QUEUE_ID = "queue_id"

        /** Input-data key: run in resume mode, the sync's songs outside a sync. */
        const val KEY_RESUME = "resume"

        /** Unique work name of the resume run ([DownloadQueueResumer]). */
        const val RESUME_WORK_NAME = "download_resume"

        /**
         * A resume run (or a single retry) that left songs for a transient failure asks
         * WorkManager to retry at most this often per start: 1, 2, 4 … minutes apart. The per-row
         * [TRANSIENT_RETRY_LIMIT] still turns a song that keeps failing into FAILED.
         */
        const val MAX_ATTEMPTS = 8

        private const val TAG = "TrackDownloadWorker"

        /** See [DownloadFailureClassifier.TRANSIENT_RETRY_LIMIT]. */
        const val TRANSIENT_RETRY_LIMIT = DownloadFailureClassifier.TRANSIENT_RETRY_LIMIT

        /**
         * Enqueues the resume run under [policy] ([DownloadQueueResumer] picks it). A run that
         * leaves songs for a transient failure retries with exponential backoff from a minute;
         * [initialDelay] holds the first start back.
         */
        fun enqueueResume(
            context: Context,
            constraints: Constraints,
            policy: ExistingWorkPolicy,
            initialDelay: Duration = Duration.ZERO,
        ): Operation {
            val request = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
                .setInputData(workDataOf(KEY_RESUME to true))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .apply {
                    if (initialDelay > Duration.ZERO) setInitialDelay(initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                }
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(RESUME_WORK_NAME, policy, request)
        }

        /**
         * Whether WorkManager stopped the worker because the app asked (Stop, Pause, Cancel), or it
         * wasn't stopped at all: the run is over. Any other reason (a lost connection, the job's time
         * limit, the OS) means WorkManager runs it again.
         */
        internal fun stoppedForGood(stopReason: Int): Boolean =
            stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP || stopReason == WorkInfo.STOP_REASON_NOT_STOPPED
    }

    /** Why a batch stopped starting songs before the end. */
    internal enum class BatchStop { STORAGE_FULL, RATE_LIMITED }

    /**
     * What a batch did.
     *
     * @property interrupted Songs that went back to PENDING for a transient failure (or a full phone).
     * @property notStarted Songs the batch never started, after it stopped.
     */
    internal data class BatchTally(
        val total: Int,
        val downloaded: Int,
        val failed: Int,
        val deferred: Int,
        val interrupted: Int,
        val notStarted: Int,
        val bytes: Long,
        val firstError: String?,
        val downloadedTrackIds: Set<Long>,
        val stoppedBy: BatchStop?,
    ) {
        /** Songs left queued: they download later, on their own or on Resume. */
        val leftWaiting: Int get() = interrupted + notStarted
    }

    /** The running counts a batch reports after each song it settles. */
    internal data class BatchProgress(
        val downloaded: Int,
        val failed: Int,
        val deferred: Int,
        val bytes: Long,
    ) {
        /** Settled for good either way: what "Downloaded X of Y" counts. */
        val completed: Int get() = downloaded + failed
    }

    /** What one failed song came to. */
    private enum class FailureFate { FAILED, INTERRUPTED, RATE_LIMITED, STORAGE_FULL }

    /**
     * The initial foreground notification. Note what this does NOT do:
     * WorkManager only calls it on its own for EXPEDITED work on Android 11
     * and below, and the sync chain is not expedited, so overriding it never
     * promotes the worker before [doWork]. Promotion happens only through the
     * [safeSetForeground] call in [doWork], which Android 12+ refuses while the
     * app is in the background — a scheduled run that starts backgrounded
     * downloads as a regular job, subject to the ~10-minute job limit.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            title = "Syncing playlists",
            text = "Preparing downloads…",
            progress = -1f, // indeterminate until we know the track count
        )
    }

    override suspend fun doWork(): Result {
        // Single-track retry path (Task 9). When the enqueuer hands us a
        // specific download_queue row, skip the full sync-chain bookkeeping
        // (orphan sweeps, requeue, parallel fan-out) and just drive that
        // one row through the downloader.
        val singleQueueId = inputData.getLong(KEY_QUEUE_ID, -1L)
        if (singleQueueId != -1L) {
            return runSingleTrackMode(singleQueueId)
        }
        if (inputData.getBoolean(KEY_RESUME, false)) {
            return runResumeMode()
        }
        return runChainMode()
    }

    /** The sync chain's download step: reconcile, then every song a sync wants. */
    @SuppressLint("NewApi") // ListenableWorker.stopReason is a field WorkManager keeps, not a platform call
    private suspend fun runChainMode(): Result {
        val startedAtMs = System.currentTimeMillis()
        val syncId = inputData.getLong(DiffWorker.KEY_SYNC_ID, -1L)
        if (syncId == -1L) {
            syncStateManager.onError("TrackDownloadWorker: missing sync ID")
            return Result.failure()
        }

        try {
            syncHistoryDao.updateStatus(syncId, SyncState.DOWNLOADING)

            // Determine which services are connected so we only retry their tracks.
            val connectedSources = connectedDownloadSources(tokenManager)
            Log.d(TAG, "Connected sources for retry: $connectedSources")

            // Diagnostic: log the actual queue state before any changes
            val statusCounts = downloadQueueDao.getStatusCounts()
            Log.i(TAG, "Queue status breakdown: ${statusCounts.map { "${it.status}=${it.count}" }}")

            syncStateManager.onVerifyingLibrary(step = 0, total = LibraryReconciliationUseCase.TOTAL_STEPS)
            if (!streamingPreference.current()) {
                val result = reconciliationUseCase.reconcile(
                    syncId = syncId,
                    onProgress = { step, total -> syncStateManager.onVerifyingLibrary(step, total) },
                    checkFileExists = fileExistenceSessionFactory.open()::exists,
                    // #77/#163: recognize files already in the user's folder
                    // (reinstall, manual copy) instead of re-downloading them.
                    // Runs before reconcile's requeue step by design.
                    adoptExistingFiles = fileAdopter::adoptAll,
                )
                Log.i(
                    TAG,
                    "Reconciliation: swept=${result.orphansSwept} staleResumed=${result.staleResumed} " +
                        "filesMissing=${result.filesMissing} requeued=${result.unqueuedRequeued} " +
                        "adopted=${result.filesAdopted}",
                )
            } else {
                // v0.9.30: Download Mode = Online (streaming index only). Pre-toggle
                // PENDING rows must NOT drain here — falling through to the collect
                // + download loop below would silently pull real files to disk even
                // though the user asked only to refresh the streamable index.
                Log.i(TAG, "Streaming mode: skipping auto-requeue of undownloaded tracks")
                syncStateManager.onDownloading(downloaded = 0, total = 0)
                return Result.success(
                    workDataOf(
                        KEY_SYNC_ID to syncId,
                        KEY_DOWNLOADED to 0,
                        KEY_FAILED to 0,
                    )
                )
            }

            val pendingItems = collectSyncDownloads(connectedSources, fallbackSyncId = syncId)
            val total = pendingItems.size

            if (total == 0) {
                // Nothing to download; pass through to finalize.
                syncStateManager.onDownloading(downloaded = 0, total = 0)
                return Result.success(
                    workDataOf(
                        KEY_SYNC_ID to syncId,
                        KEY_DOWNLOADED to 0,
                        KEY_FAILED to 0,
                    )
                )
            }

            // Paused by the user: the sync still refreshed the playlists and queued
            // their songs, but nothing downloads until Resume ("Sync now" clears the
            // pause before it starts a sync).
            if (queueState.isPaused()) {
                Log.i(TAG, "Downloads are paused: leaving $total song(s) queued")
                syncStateManager.onDownloading(downloaded = 0, total = 0)
                return Result.success(
                    workDataOf(
                        KEY_SYNC_ID to syncId,
                        KEY_DOWNLOADED to 0,
                        KEY_FAILED to 0,
                        KEY_WAITING to total,
                    )
                )
            }

            // Promote to a foreground service with the real track count. This
            // is the ONLY promotion: getForegroundInfo() alone does not run a
            // non-expedited worker in the foreground. Best-effort — refused
            // when the app is backgrounded (see safeSetForeground).
            syncStateManager.onDownloading(downloaded = 0, total = total)
            safeSetForeground(createForegroundInfo(downloaded = 0, total = total))
            // Every per-track update reposts the progress notification under
            // the same id, so it must carry the Pause action and the tap too, or
            // the first update strips them from the foreground notification.
            val pauseIntent = syncNotificationManager.pauseDownloadsIntent()
            val openIntent = syncNotificationManager.openDownloadsIntent()
            val playlistsChecked = inputData.getInt(DiffWorker.KEY_PLAYLISTS_CHECKED, 0)

            val tally = downloadBatch(pendingItems) { progress ->
                // Deferred tracks count toward progress (the decision is
                // made) but are reported separately so "Downloaded X of Y"
                // never lies about files on disk.
                val completed = progress.completed
                val deferred = progress.deferred
                val resolved = completed + deferred
                syncStateManager.onDownloading(
                    downloaded = completed,
                    total = total,
                    deferred = deferred,
                )
                syncNotificationManager.updateProgress(
                    title = "Syncing playlists",
                    text = if (deferred > 0) {
                        "Downloaded $completed of $total · $deferred waiting"
                    } else {
                        "Downloaded $completed of $total"
                    },
                    progress = 0.25f + 0.70f * (resolved.toFloat() / total),
                    pauseIntent = pauseIntent,
                    contentIntent = openIntent,
                )

                // Flush sync history tallies every 10 tracks for crash resilience.
                if (completed % 10 == 0) {
                    syncHistoryDao.updateCounts(
                        id = syncId,
                        playlistsChecked = playlistsChecked,
                        newTracksFound = total,
                        tracksDownloaded = progress.downloaded,
                        tracksFailed = progress.failed,
                        bytesDownloaded = progress.bytes,
                    )
                }
            }

            // Final tally flush.
            val finalDownloaded = tally.downloaded
            val finalFailed = tally.failed

            syncHistoryDao.updateCounts(
                id = syncId,
                playlistsChecked = playlistsChecked,
                newTracksFound = total,
                tracksDownloaded = finalDownloaded,
                tracksFailed = finalFailed,
                bytesDownloaded = tally.bytes,
            )

            // Store first download error in sync history for on-device debugging.
            val firstErr = tally.firstError
            if (firstErr != null) {
                val summary = "$finalFailed/$total downloads failed. First error: $firstErr"
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.DOWNLOADING,
                    errorMessage = summary.take(1000),
                )
            }

            val durationMs = System.currentTimeMillis() - startedAtMs
            val durationSec = durationMs / 1000.0
            val tpm = if (durationSec > 0) finalDownloaded / (durationSec / 60.0) else 0.0
            Log.i(
                TAG,
                "PerfSummary: ${finalDownloaded} tracks in ${"%.1f".format(durationSec)}s " +
                    "(${"%.1f".format(tpm)} tracks/min); failed=${finalFailed}; " +
                    "deferred=${tally.deferred}; waiting=${tally.leftWaiting}; stoppedBy=${tally.stoppedBy}",
            )

            // #421: leave a receipt for deferrals — a sync that "downloaded 0
            // of 5" with no explanation reads as broken when the truth is
            // "waiting for a lossless source".
            val finalDeferred = tally.deferred
            if (finalDeferred > 0) {
                syncLog.info(
                    "$finalDeferred song${if (finalDeferred == 1) "" else "s"} waiting — not " +
                        "available in your Download quality yet. They'll download automatically " +
                        "when they are (or turn on the lossy fallback in Audio & Quality to get " +
                        "them now)",
                )
            }

            runLosslessUpgradeSweep(tally.downloadedTrackIds)

            afterBatch(tally)
            if (tally.stoppedBy == null && tally.interrupted > 0) {
                // Songs a network blip (or a mid-run sign-in problem) sent back to the
                // queue: they used to wait for tomorrow's sync. The resume run takes
                // them in a couple of minutes, under the background rules.
                runCatching { resumer.resume(ResumeTrigger.AUTO, DownloadQueueResumer.AFTER_SYNC_DELAY) }
                    .onFailure { Log.w(TAG, "couldn't schedule the resume run for ${tally.interrupted} song(s)", it) }
            }

            return Result.success(
                workDataOf(
                    KEY_SYNC_ID to syncId,
                    KEY_DOWNLOADED to finalDownloaded,
                    KEY_FAILED to finalFailed,
                    KEY_WAITING to tally.leftWaiting,
                )
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // The app stopped the sync (Stop, Pause, the notification) or WorkManager
            // pulled the plug. Running rows handed their claims back as they stopped
            // (see downloadBatch), so nothing is left showing as downloading.
            val forGood = stoppedForGood(stopReason)
            Log.i(TAG, "Sync download step stopped (reason=$stopReason, for good: $forGood)")
            if (forGood) {
                // Mark the sync history row CANCELLED and reset the in-memory phase back
                // to Idle so the UI stops showing a progress card. DO NOT mark the sync
                // FAILED — cancellation isn't a failure (it renders as a neutral receipt,
                // not a red one).
                // NonCancellable: this coroutine is already cancelled, so a plain
                // suspend DAO call throws on entry and the row stayed DOWNLOADING
                // ("Running") until the next startup flipped it to Interrupted.
                withContext(NonCancellable) {
                    syncHistoryDao.updateStatus(
                        id = syncId,
                        status = SyncState.CANCELLED,
                        completedAt = System.currentTimeMillis(),
                        errorMessage = "Cancelled",
                    )
                }
                syncStateManager.reset()
            } else {
                // A lost connection, the job's time limit, the OS: WorkManager runs this
                // step again, so the sync isn't over. Its history row stays open and the
                // Sync tab says it is waiting, instead of "Cancelled" followed by a sync
                // reappearing later with no explanation.
                syncStateManager.onWaitingForNetwork()
            }
            // Only SyncFinalizeWorker dismissed the progress notification, and a
            // stopped chain never reaches it. When the run was not foreground
            // (a scheduled sync with the app in the background) the per-track
            // updates had posted a plain ongoing notification, so "Downloaded
            // 37 of 120" stuck around, undismissable, until a later sync ended.
            syncNotificationManager.cancelProgress()
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Download worker failed", e)
            syncNotificationManager.cancelProgress()
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message,
            )
            syncStateManager.onError("Download failed: ${e.message}", e)
            return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
        }
    }

    /**
     * Resume mode: the songs a sync would download, outside a sync. Started by
     * [DownloadQueueResumer] (a cold start, the app coming back, a sync that left songs, Resume),
     * and retried by WorkManager with backoff while songs come back for a transient failure.
     * Doesn't publish into [SyncStateManager]: that is the sync's, and a sync may run beside it.
     */
    private suspend fun runResumeMode(): Result {
        if (streamingPreference.current()) {
            // Online mode never downloads a sync's rows, the same rule as the sync's own step.
            Log.i(TAG, "Resume: Online mode, nothing to download")
            return Result.success()
        }
        if (queueState.isPaused() || queueState.storageFull.value) {
            Log.i(TAG, "Resume: downloads are paused or the phone is full; not starting")
            return Result.success()
        }
        if (resumer.isSyncDownloadRunning()) {
            // The sync's own step takes the same rows; it leaves any it can't finish for the next resume.
            Log.i(TAG, "Resume: a sync is downloading; leaving the queue to it")
            return Result.success()
        }
        // Claims no live job holds (a killed process, a stopped worker) go back to waiting first.
        downloadJobs.releaseDeadClaims(downloadQueueDao)
        val items = collectSyncDownloads(connectedDownloadSources(tokenManager), fallbackSyncId = null)
        if (items.isEmpty()) {
            Log.i(TAG, "Resume: nothing waiting")
            return Result.success()
        }
        val total = items.size
        Log.i(TAG, "Resume: downloading $total song(s) (attempt ${runAttemptCount + 1})")
        val title = applicationContext.getString(R.string.notif_downloads_title)
        safeSetForeground(
            createForegroundInfo(
                title = title,
                text = applicationContext.getString(R.string.notif_downloads_preparing),
                progress = -1f,
                notificationId = SyncNotificationManager.NOTIFICATION_ID_RESUME,
                pause = true,
            ),
        )
        val pauseIntent = syncNotificationManager.pauseDownloadsIntent()
        val openIntent = syncNotificationManager.openDownloadsIntent()
        try {
            val tally = downloadBatch(items) { progress ->
                val done = progress.completed
                syncNotificationManager.updateProgress(
                    title = title,
                    text = if (progress.deferred > 0) {
                        applicationContext.getString(R.string.notif_downloads_progress_waiting, done, total, progress.deferred)
                    } else {
                        applicationContext.getString(R.string.notif_downloads_progress, done, total)
                    },
                    progress = (done + progress.deferred).toFloat() / total,
                    pauseIntent = pauseIntent,
                    contentIntent = openIntent,
                    notificationId = SyncNotificationManager.NOTIFICATION_ID_RESUME,
                )
            }
            Log.i(
                TAG,
                "Resume: downloaded=${tally.downloaded} failed=${tally.failed} deferred=${tally.deferred} " +
                    "waiting=${tally.leftWaiting} stoppedBy=${tally.stoppedBy}",
            )
            afterBatch(tally)
            return when {
                tally.stoppedBy != null -> Result.success()
                tally.interrupted > 0 && runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
                else -> Result.success()
            }
        } finally {
            // A foreground notification goes with the run; one posted while the app was in the
            // background would stick around, ongoing, after it.
            syncNotificationManager.cancelResumeProgress()
        }
    }

    /**
     * The songs a sync's download step takes: every PENDING row a sync wants plus retryable
     * FAILED ones, deduplicated by song. With no source known (never: BOTH is always there) the
     * sync's own rows, as before.
     */
    private suspend fun collectSyncDownloads(
        connectedSources: List<String>,
        fallbackSyncId: Long?,
    ): List<DownloadQueueEntity> {
        // Collect ALL pending items (from any sync) plus retryable failed items.
        val allPending = if (connectedSources.isNotEmpty()) {
            downloadQueueDao.getAllPendingBySources(connectedSources)
        } else {
            fallbackSyncId?.let { downloadQueueDao.getPendingBySyncId(it) }.orEmpty()
        }
        val retryItems = if (connectedSources.isNotEmpty()) {
            downloadQueueDao.getRetryableBySources(connectedSources)
        } else {
            emptyList()
        }
        // Deduplicate (a track could appear in both lists)
        val seen = mutableSetOf<Long>()
        val combined = allPending + retryItems
        val pendingItems = combined.filter { seen.add(it.trackId) }
        Log.d(TAG, "Download queue: ${allPending.size} pending + ${retryItems.size} retry = ${pendingItems.size} total (deduped)")
        // QueueTrace diagnostic: log per-trackId queue-row counts so we can spot duplicate-queueing patterns.
        val rowsByTrackId = combined.groupBy { it.trackId }
        val duplicates = rowsByTrackId.filter { it.value.size > 1 }
        if (duplicates.isNotEmpty()) {
            Log.w(TAG, "QueueTrace: ${duplicates.size} track_ids have multiple queue rows: ${duplicates.entries.take(10).map { (tid, rows) -> "$tid×${rows.size}" }}${if (duplicates.size > 10) "...(${duplicates.size - 10} more)" else ""}")
        }
        return pendingItems
    }

    /**
     * After a batch, either mode: a full phone holds the queue and says so; YouTube asking us to
     * wait schedules the next try in about 15 minutes and says so quietly; a batch that landed a
     * song proves there is room again.
     */
    private suspend fun afterBatch(tally: BatchTally) {
        when (tally.stoppedBy) {
            BatchStop.STORAGE_FULL -> {
                Log.w(TAG, "Out of space: ${tally.leftWaiting} song(s) left queued until Resume")
                queueState.setStorageFull(true)
                syncNotificationManager.showStorageFull()
            }
            BatchStop.RATE_LIMITED -> {
                val delay = DownloadQueueResumer.RATE_LIMIT_DELAY
                Log.w(TAG, "YouTube asked us to wait: ${tally.leftWaiting} song(s) left queued, next try in $delay")
                syncNotificationManager.showRateLimited(System.currentTimeMillis() + delay.inWholeMilliseconds)
                runCatching { resumer.resume(ResumeTrigger.AUTO, delay) }
                    .onFailure { Log.w(TAG, "couldn't schedule the resume run after the rate limit", it) }
            }
            null -> if (tally.downloaded > 0) queueState.setStorageFull(false)
        }
    }

    /**
     * Downloads [items], [DownloadSlots.COUNT] at a time, and reports each settled song through
     * [onResolved]. Shared by chain and resume mode.
     *
     * All items are launched as concurrent coroutines and DownloadSlots.COUNT of them run at a
     * time (downloadSlots, the same slots DownloadManager.downloadTrack() and other workers
     * take). Previously this was a sequential for loop, which meant the Semaphore only ever saw
     * one caller and concurrency was always 1. With this, 8 downloads run in parallel, giving an
     * ~8x speedup (from ~4 tracks/min to ~32 tracks/min). Thread-safe counters ensure correct
     * tallies despite concurrent updates from multiple coroutines.
     *
     * A full phone or YouTube asking us to wait stops the batch starting songs: every song after
     * it would fail the same way, 8 at a time, each using a retry. The songs it never starts stay
     * PENDING with no retry used; the ones already running finish.
     */
    private suspend fun downloadBatch(
        items: List<DownloadQueueEntity>,
        onResolved: suspend (BatchProgress) -> Unit,
    ): BatchTally {
        val downloadedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        // #421: strict-FLAC deferrals ("wait for a lossless source") are a
        // RESOLVED outcome — counted so progress moves and the user is told,
        // instead of the sync sitting at "Downloading 0/N" and going quiet.
        val deferredCount = AtomicInteger(0)
        val interruptedCount = AtomicInteger(0)
        val notStartedCount = AtomicInteger(0)
        val totalBytesDownloaded = AtomicLong(0)
        val firstError = AtomicReference<String?>(null)
        val stoppedBy = AtomicReference<BatchStop?>(null)
        val downloadedTrackIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

        /** Tallies a failed song's fate, and stops the batch on a full phone or a rate limit. */
        fun count(fate: FailureFate, error: String?, recordError: Boolean) {
            when (fate) {
                FailureFate.FAILED -> {
                    if (recordError && error != null) firstError.compareAndSet(null, error)
                    failedCount.incrementAndGet()
                }
                FailureFate.INTERRUPTED -> interruptedCount.incrementAndGet()
                FailureFate.RATE_LIMITED -> {
                    interruptedCount.incrementAndGet()
                    stoppedBy.compareAndSet(null, BatchStop.RATE_LIMITED)
                }
                FailureFate.STORAGE_FULL -> {
                    interruptedCount.incrementAndGet()
                    // A full phone wins over a rate limit: it needs the user, a rate limit only time.
                    stoppedBy.set(BatchStop.STORAGE_FULL)
                }
            }
        }

        supervisorScope {
            for (queueItem in items) {
                launch {
                    // A song is claimed (and so shown as downloading) only once it holds
                    // one of the slots: launching all and letting each wait inside
                    // DownloadManager marked every pending row IN_PROGRESS at once.
                    downloadSlots.withSlot {
                        // The batch stopped (full phone, rate limit) while this one waited
                        // for a slot: it stays PENDING with no retry used.
                        if (stoppedBy.get() != null) {
                            notStartedCount.incrementAndGet()
                            return@launch
                        }
                        val itemJob = currentCoroutineContext().job
                        // Claim and register as one step, so a tap never sees this
                        // row IN_PROGRESS without its live job (DownloadJobRegistry).
                        if (!downloadJobs.claimAndRegister(queueItem.id, itemJob) {
                                downloadQueueDao.claimForDownload(queueItem.id) != 0
                            }
                        ) return@launch
                        if (downloadQueueDao.getStatusById(queueItem.id) == DownloadStatus.SKIPPED) {
                            // Cancelled as it was claimed: a replacement keeps its file.
                            fileReplacement.restoreAfterStop(queueItem.trackId)
                            return@launch
                        }
                        // The track's file before this download, read before it
                        // starts: a re-download deletes it once the row records the
                        // new file, and one that fails for good restores it
                        // (TrackFileReplacement).
                        var keptPath: String? = null
                        try {
                            val trackEntity = trackDao.getById(queueItem.trackId)
                            if (trackEntity == null) {
                                Log.w(TAG, "Track ${queueItem.trackId} not found in DB, skipping")
                                val err = "Track not found in database"
                                val type = classifier.classify(
                                    FailureContext(
                                        phase = DownloadPhase.MATCHING,
                                        errorText = err,
                                        httpStatus = null,
                                        causeChain = emptyList(),
                                    )
                                )
                                downloadQueueDao.failIfInProgress(
                                    id = queueItem.id,
                                    errorMessage = err.take(1000),
                                    failureType = type,
                                    completedAt = System.currentTimeMillis(),
                                )
                                failedCount.incrementAndGet()
                                return@launch
                            }

                            // v0.9.15: Last-line defense. The DAO feeders
                            // (getAllPendingBySources etc.) already exclude
                            // blocklisted tracks, but a track could enter the
                            // queue via a path that runs BEFORE this worker
                            // sees it. If it's blocked, drop the queue entry
                            // and skip — never download a blocked identity.
                            if (blocklistGuard.isBlocked(
                                    artist = trackEntity.artist,
                                    title = trackEntity.title,
                                    spotifyUri = trackEntity.spotifyUri,
                                    youtubeId = trackEntity.youtubeId,
                                )) {
                                Log.d(TAG, "Skipping blocked track ${trackEntity.id} (${trackEntity.artist} - ${trackEntity.title})")
                                downloadQueueDao.deleteByTrackId(trackEntity.id)
                                return@launch
                            }

                            if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                                downloadQueueDao.completeIfInProgress(
                                    id = queueItem.id,
                                    completedAt = System.currentTimeMillis(),
                                )
                                downloadedCount.incrementAndGet()
                                return@launch
                            }

                            keptPath = trackEntity.filePath
                            val track = trackEntity.toDomain()
                            val outcome = downloadSlots.holding {
                                trackDownloader.downloadTrack(
                                    track = track,
                                    preResolvedUrl = queueItem.youtubeUrl,
                                )
                            }

                            when (outcome) {
                                is TrackDownloadOutcome.Success -> {
                                    // Cancellation can land between "file written" and
                                    // "row marked COMPLETED". Mirrors the single-track
                                    // guard below: a cancelled row must not become a
                                    // downloaded track, and the bytes that arrived after
                                    // the user said stop don't get to stay on disk.
                                    // NonCancellable because cancel() also cancels this
                                    // job — without it the status read throws instead of
                                    // answering, and the file leaks. A new file that
                                    // replaced the track's own in place is its only copy
                                    // and stays (see discardCancelledDownload).
                                    val cancelledMidFlight = withContext(NonCancellable) {
                                        if (downloadQueueDao.getStatusById(queueItem.id) != DownloadStatus.SKIPPED) {
                                            false
                                        } else {
                                            fileReplacement.discardCancelledDownload(
                                                trackId = queueItem.trackId,
                                                keptPath = trackEntity.filePath,
                                                newPath = outcome.filePath,
                                            )
                                            true
                                        }
                                    }
                                    if (cancelledMidFlight) return@launch

                                    val fileSize = try {
                                        File(outcome.filePath).length()
                                    } catch (_: Exception) { 0L }

                                    // Extract metadata BEFORE markAsDownloaded so we can
                                    // persist bit-depth + sample-rate in the same row write.
                                    // The file is on disk at this point — `outcome.filePath`
                                    // is final, not a temp path.
                                    val meta = audioDurationExtractor.extract(outcome.filePath)

                                    val recorded = trackDao.markAsDownloaded(
                                        trackId = queueItem.trackId,
                                        filePath = outcome.filePath,
                                        fileSizeBytes = fileSize,
                                        sampleRateHz = meta?.sampleRateHz,
                                        bitsPerSample = meta?.bitsPerSample,
                                    )
                                    // Only now that the row records the new file may the
                                    // old one go: a cancel or a dead process before this
                                    // write left the row on a deleted file.
                                    if (recorded > 0) {
                                        fileReplacement.deleteReplacedFile(
                                            trackId = queueItem.trackId,
                                            oldPath = trackEntity.filePath,
                                            newPath = outcome.filePath,
                                        )
                                    }

                                    if (meta != null) {
                                        // Format: source-of-truth for Library Health.
                                        // Write whenever the codec is known, even when
                                        // MMR couldn't compute a bitrate — variable-
                                        // bitrate codecs (FLAC) routinely return
                                        // bitrate=0 from MMR, and the prior gate
                                        // `bitrateKbps > 0` silently dropped the
                                        // format write for every FLAC download,
                                        // leaving file_format stuck at the legacy
                                        // 'opus' default. The DAO writes both columns
                                        // in one statement; a 0 quality value is
                                        // accepted and means "unknown" to readers
                                        // (Library Health renders it as `—`).
                                        if (meta.format != "unknown") {
                                            runCatching {
                                                trackDao.setFormatAndQuality(
                                                    trackId = queueItem.trackId,
                                                    fileFormat = meta.format,
                                                    qualityKbps = meta.bitrateKbps,
                                                )
                                            }.onFailure { e ->
                                                Log.w(TAG, "setFormatAndQuality failed for ${queueItem.trackId}", e)
                                            }
                                        }

                                        // Duration reconciliation: the file is
                                        // truth. Overwrite the DB when it's missing
                                        // OR when the value diverges from the file
                                        // by >10% — that gap is the signature of
                                        // yt-dlp matching a different cut (live,
                                        // extended) than Spotify's metadata
                                        // implied.
                                        if (meta.durationMs > 0) {
                                            val dbMs = trackEntity.durationMs
                                            val drift = if (dbMs > 0) {
                                                kotlin.math.abs(meta.durationMs - dbMs).toDouble() /
                                                    meta.durationMs.toDouble()
                                            } else 1.0
                                            if (dbMs == 0L || drift > 0.10) {
                                                runCatching {
                                                    trackDao.setDuration(queueItem.trackId, meta.durationMs)
                                                }.onSuccess {
                                                    if (dbMs > 0) {
                                                        Log.i(
                                                            TAG,
                                                            "duration reconciled: ${track.artist} - ${track.title} " +
                                                                "${dbMs}ms → ${meta.durationMs}ms (${(drift * 100).toInt()}% drift)",
                                                        )
                                                    }
                                                }.onFailure { e ->
                                                    Log.w(TAG, "setDuration failed for ${queueItem.trackId}", e)
                                                }
                                            }
                                        }
                                    }
                                    downloadQueueDao.completeIfInProgress(
                                        id = queueItem.id,
                                        completedAt = System.currentTimeMillis(),
                                    )
                                    totalBytesDownloaded.addAndGet(fileSize)
                                    downloadedCount.incrementAndGet()
                                    downloadedTrackIds.add(queueItem.trackId)
                                }
                                is TrackDownloadOutcome.Unmatched -> {
                                    val err = "No YouTube match for: ${track.artist} - ${track.title}"
                                    Log.w(TAG, err)
                                    downloadQueueDao.incrementRetryCount(queueItem.id)
                                    downloadQueueDao.failIfInProgress(
                                        id = queueItem.id,
                                        failureType = DownloadFailureType.NO_MATCH,
                                        errorMessage = err,
                                        rejectedVideoId = outcome.rejectedVideoId,
                                        completedAt = System.currentTimeMillis(),
                                    )
                                    fileReplacement.restoreKeptFile(queueItem.trackId, trackEntity.filePath)
                                    firstError.compareAndSet(null, err)
                                    failedCount.incrementAndGet()
                                }
                                is TrackDownloadOutcome.Failed -> {
                                    Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
                                    val type = classifier.classify(
                                        FailureContext(
                                            phase = DownloadPhase.DOWNLOADING,
                                            errorText = outcome.error,
                                            httpStatus = null,
                                            causeChain = emptyList(),
                                        )
                                    )
                                    val truncated = outcome.error.take(1000)
                                    // A failure for good bumps retry_count separately to
                                    // match prior tally semantics for terminal failures.
                                    val fate = settleFailure(queueItem, type, truncated, trackEntity.filePath, countRetryWhenFinal = true)
                                    count(fate, truncated, recordError = true)
                                }
                                is TrackDownloadOutcome.Deferred -> {
                                    // v0.9.17 strict-FLAC: lossless source unavailable
                                    // and yt-dlp fallback off. The DAO row was already
                                    // written to WAITING_FOR_LOSSLESS by
                                    // TrackDownloaderImpl — do NOT increment retries,
                                    // do NOT mark FAILED, do NOT tally as failure.
                                    // LosslessRetryScheduler owns the re-attempt signal.
                                    deferredCount.incrementAndGet()
                                    Log.i(TAG, "Deferred (waiting for lossless): ${track.artist} - ${track.title}")
                                }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            // The song (Cancel on its row, Clear queue) or the whole worker
                            // (Stop, Pause, a lost constraint, the OS) was stopped. Never
                            // FAILED: marking thousands of in-flight rows FAILED on cancel
                            // was the root cause of the "2325 failed rows after Stop"
                            // smoke-test crash.
                            //
                            // NonCancellable around the DAO calls ONLY: this coroutine is
                            // already cancelled, so a plain suspend DAO call throws
                            // CancellationException instead of answering, and the per-item
                            // cancel could never be told apart from a worker-wide stop. The
                            // worker body stays cancellable.
                            val userCancelled = withContext(NonCancellable) {
                                val skipped = downloadQueueDao.getStatusById(queueItem.id) == DownloadStatus.SKIPPED
                                // The worker stopped: hand the claim back so the next run
                                // takes the row. Left IN_PROGRESS, the song showed as
                                // downloading (the Sync tab's "N downloading") until a reset,
                                // and claimForDownload skipped it. A no-op unless IN_PROGRESS.
                                if (!skipped) downloadQueueDao.releaseClaim(queueItem.id)
                                skipped
                            }
                            // Either way, a track handed back for a better copy
                            // (markForReplacement) that stopped before its row
                            // recorded a new file gets its kept file back as its
                            // download: before, it only did on FAILED or after a
                            // commit, and one stopped mid-download dropped out of
                            // the Library. A no-op for every other track.
                            fileReplacement.restoreAfterStop(queueItem.trackId)
                            if (userCancelled) {
                                return@launch
                            }
                            throw ce
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download track ${queueItem.trackId}", e)
                            val type = classifier.classify(
                                FailureContext(
                                    phase = DownloadPhase.DOWNLOADING,
                                    errorText = e.message,
                                    httpStatus = null,
                                    causeChain = DownloadFailureClassifier.causeChainOf(e),
                                )
                            )
                            val truncated = e.message?.take(1000)
                            val fate = settleFailure(queueItem, type, truncated, keptPath, countRetryWhenFinal = false)
                            count(fate, truncated, recordError = false)
                        }

                        onResolved(
                            BatchProgress(
                                downloaded = downloadedCount.get(),
                                failed = failedCount.get(),
                                deferred = deferredCount.get(),
                                bytes = totalBytesDownloaded.get(),
                            ),
                        )
                    }
                }
            }
        }
        // supervisorScope waits for all launched coroutines to complete.

        return BatchTally(
            total = items.size,
            downloaded = downloadedCount.get(),
            failed = failedCount.get(),
            deferred = deferredCount.get(),
            interrupted = interruptedCount.get(),
            notStarted = notStartedCount.get(),
            bytes = totalBytesDownloaded.get(),
            firstError = firstError.get(),
            downloadedTrackIds = downloadedTrackIds.toSet(),
            stoppedBy = stoppedBy.get(),
        )
    }

    /**
     * One failed song, by the one taxonomy ([DownloadFailureClassifier.laneFor]):
     * - a failure for good (the source said no, or a transient one that keeps happening) is
     *   FAILED and shows in Failed downloads; a file kept for this re-download is the track's
     *   download again;
     * - a transient failure (network, mid-run sign-in, UNKNOWN) or a rate limit goes back to
     *   PENDING with one retry used, keeping its error for telemetry;
     * - a full phone goes back to PENDING with no retry used.
     */
    private suspend fun settleFailure(
        queueItem: DownloadQueueEntity,
        type: DownloadFailureType,
        message: String?,
        keptPath: String?,
        countRetryWhenFinal: Boolean,
    ): FailureFate = when (DownloadFailureClassifier.laneFor(type, queueItem.retryCount)) {
        FailureLane.FINAL -> {
            if (countRetryWhenFinal) downloadQueueDao.incrementRetryCount(queueItem.id)
            downloadQueueDao.failIfInProgress(
                id = queueItem.id,
                errorMessage = message,
                failureType = type,
                completedAt = System.currentTimeMillis(),
            )
            // Failed for good: a file kept for this re-download is the track's download again.
            fileReplacement.restoreKeptFile(queueItem.trackId, keptPath)
            FailureFate.FAILED
        }
        FailureLane.RETRY -> {
            // revertToPendingAfterInterruption increments retry_count itself.
            downloadQueueDao.revertToPendingAfterInterruption(
                queueId = queueItem.id,
                lastErrorMessage = message,
                lastFailureType = type,
            )
            Log.i(TAG, "Interrupted (type=$type), keeping queue ${queueItem.id} PENDING for a retry: ${message?.take(120)}")
            FailureFate.INTERRUPTED
        }
        FailureLane.RATE_LIMITED -> {
            downloadQueueDao.revertToPendingAfterInterruption(
                queueId = queueItem.id,
                lastErrorMessage = message,
                lastFailureType = type,
            )
            Log.w(TAG, "Rate limited, keeping queue ${queueItem.id} PENDING and starting no more: ${message?.take(120)}")
            FailureFate.RATE_LIMITED
        }
        FailureLane.STORAGE_FULL -> {
            downloadQueueDao.revertToPendingKeepingRetries(
                queueId = queueItem.id,
                lastErrorMessage = message,
                lastFailureType = type,
            )
            Log.w(TAG, "Out of space, keeping queue ${queueItem.id} PENDING (no retry used) and stopping the batch")
            FailureFate.STORAGE_FULL
        }
    }

    /**
     * Single-track retry path. Reads exactly one `download_queue` row and
     * drives it through the same [TrackDownloader] the chain uses, then
     * translates the [TrackDownloadOutcome] into a queue update. Used by
     * `SingleTrackDownloadEnqueuer` (Task 9.5) — one OneTimeWorkRequest per
     * row.
     *
     * This path deliberately omits the chain-mode bookkeeping (sync history
     * tally, orphan sweep, requeue, foreground-progress fraction over
     * `total`), since the retry is a single sequential invocation and the
     * Failed Downloads viewer drives its own UI off the queue Flow.
     */
    private suspend fun runSingleTrackMode(queueId: Long): Result {
        // Single-track mode does NOT publish into SyncStateManager.phase — that singleton
        // flow is shared with a possibly-running chain sync and overwriting it would
        // stomp the chain's progress counter. The Failed Downloads viewer reads queue
        // rows directly via DownloadQueueDao.getFailedDownloads().
        val entry = downloadQueueDao.getById(queueId)
        if (entry == null) {
            Log.w(TAG, "Single-track mode: queue row $queueId not found")
            return Result.success()
        }
        val trackEntity = trackDao.getById(entry.trackId)
        if (trackEntity == null) {
            Log.w(TAG, "Single-track mode: track ${entry.trackId} not found for queue $queueId")
            return Result.failure()
        }

        val track = trackEntity.toDomain()
        Log.i(
            TAG,
            "Single-track mode: queueId=$queueId trackId=${entry.trackId} (${track.artist} - ${track.title})",
        )

        // Swap the foreground notification to single-track wording so the user
        // sees "Retrying download — Artist – Title" instead of the chain-mode
        // "Syncing playlists / Preparing downloads…" copy.
        safeSetForeground(
            createForegroundInfo(
                title = "Retrying download",
                text = "${track.artist} – ${track.title}",
                progress = -1f, // indeterminate — single-track has no N-of-M progress
            ),
        )

        // Manual retries deliberately do NOT increment retry_count — user-initiated
        // attempts should not consume the auto-retry budget owned by chain mode.
        // A transient failure here goes back to PENDING with its count unchanged
        // (settleSingleFailure), and MAX_ATTEMPTS bounds the retries instead.

        // Mark the row IN_PROGRESS before invoking the downloader so the Failed
        // Downloads viewer reflects the active state and spam-tapping Retry is
        // idempotent (the row leaves FAILED immediately and re-enqueue paths
        // can no-op on non-FAILED rows). Relies on updateStatus's defaults to
        // null out error_message / completed_at / rejected_video_id and reset
        // failure_type to NONE, matching the chain-mode IN_PROGRESS call.
        // The row is claimed only once a download slot is free (DownloadSlots), so it waits as Pending behind a busy sync.
        return downloadSlots.withSlot { claimAndDownload(entry, track) }
    }

    /** [runSingleTrackMode] from the claim on, holding a download slot. */
    private suspend fun claimAndDownload(
        entry: com.stash.core.data.db.entity.DownloadQueueEntity,
        track: com.stash.core.model.Track,
    ): Result {
        val itemJob = currentCoroutineContext().job
        // Claimed and registered as one step: see DownloadJobRegistry.claimAndRegister.
        if (!downloadJobs.claimAndRegister(entry.id, itemJob) { downloadQueueDao.claimForDownload(entry.id) != 0 }) {
            return Result.success()
        }
        // If WorkManager stops the work anywhere after the claim — an unmet constraint
        // (battery low, network gone) or the OS reclaiming the job — hand the claim back
        // before rethrowing. WorkManager reruns the work when the constraint clears, and
        // claimForDownload only takes PENDING/FAILED, so a claim left IN_PROGRESS made
        // that rerun return success without downloading anything (seen on-device
        // 2026-09-22, the stop landing after DownloadManager had already returned).
        //
        // A process killed outright still leaves IN_PROGRESS until the next live-job-aware
        // reset (cold start, a sync's reconcile, the resume run), or until a tap on the
        // song, which resets a row whose job isn't live (MusicRepositoryImpl.queueDownload).
        return try {
            downloadClaimed(entry, track)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { downloadQueueDao.releaseClaim(entry.id) } // no-op unless IN_PROGRESS
            // A replacement stopped before its row recorded a new file keeps its
            // file as its download (see the chain-mode cancel branch).
            fileReplacement.restoreAfterStop(entry.trackId)
            throw ce
        }
    }

    /** The claimed part of [runSingleTrackMode]; a stop in here hands the claim back. */
    private suspend fun downloadClaimed(
        entry: com.stash.core.data.db.entity.DownloadQueueEntity,
        track: com.stash.core.model.Track,
    ): Result {
        if (downloadQueueDao.getStatusById(entry.id) == DownloadStatus.SKIPPED) {
            // Cancelled as it was claimed: a replacement keeps its file.
            fileReplacement.restoreAfterStop(entry.trackId)
            return Result.success()
        }

        val outcome = try {
            downloadSlots.holding {
                trackDownloader.downloadTrack(
                    track = track,
                    preResolvedUrl = entry.youtubeUrl,
                )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // WorkManager stopped us. Don't mark the row FAILED: a user skip
            // returns quietly, anything else rethrows to runSingleTrackMode,
            // which hands the claim back for the rerun.
            //
            // NonCancellable around the read ONLY — see the chain-mode guard.
            val userCancelled = withContext(NonCancellable) {
                downloadQueueDao.getStatusById(entry.id) == DownloadStatus.SKIPPED
            }
            if (userCancelled) {
                // runSingleTrackMode restores after a rethrow; this return skips it.
                fileReplacement.restoreAfterStop(entry.trackId)
                return Result.success()
            }
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Single-track downloader threw for queue ${entry.id}", e)
            val truncated = (e.message ?: e::class.simpleName ?: "Unknown error").take(1000)
            val type = classifier.classify(
                FailureContext(
                    phase = DownloadPhase.DOWNLOADING,
                    errorText = e.message,
                    httpStatus = null,
                    causeChain = DownloadFailureClassifier.causeChainOf(e),
                ),
            )
            return settleSingleFailure(entry, track, type, truncated)
        }

        when (outcome) {
            is TrackDownloadOutcome.Success -> {
                // NonCancellable: cancel() cancels this job too, so an unwrapped
                // read would throw and leave the just-landed file behind.
                val cancelledMidFlight = withContext(NonCancellable) {
                    if (downloadQueueDao.getStatusById(entry.id) != DownloadStatus.SKIPPED) {
                        false
                    } else {
                        fileReplacement.discardCancelledDownload(
                            trackId = entry.trackId,
                            keptPath = track.filePath,
                            newPath = outcome.filePath,
                        )
                        true
                    }
                }
                if (cancelledMidFlight) {
                    return Result.success()
                }
                // Persist the downloaded state on the TRACK row, mirroring chain
                // mode (doWork). Without this the file lands on disk but
                // tracks.is_downloaded / file_path stay unset, so the UI keeps
                // showing the track as not-downloaded and it isn't playable from
                // disk. (Pre-v0.9.63 single-track mode skipped this entirely.)
                val fileSize = try { File(outcome.filePath).length() } catch (_: Exception) { 0L }
                val meta = audioDurationExtractor.extract(outcome.filePath)
                val recorded = trackDao.markAsDownloaded(
                    trackId = entry.trackId,
                    filePath = outcome.filePath,
                    fileSizeBytes = fileSize,
                    sampleRateHz = meta?.sampleRateHz,
                    bitsPerSample = meta?.bitsPerSample,
                )
                // The old file goes only once the row records the new one.
                if (recorded > 0) {
                    fileReplacement.deleteReplacedFile(entry.trackId, track.filePath, outcome.filePath)
                }
                if (meta != null && meta.format != "unknown") {
                    runCatching {
                        trackDao.setFormatAndQuality(
                            trackId = entry.trackId,
                            fileFormat = meta.format,
                            qualityKbps = meta.bitrateKbps,
                        )
                    }.onFailure { e -> Log.w(TAG, "setFormatAndQuality failed for ${entry.trackId}", e) }
                }
                downloadQueueDao.completeIfInProgress(
                    id = entry.id,
                    completedAt = System.currentTimeMillis(),
                )
                // A song that landed proves there is room again.
                queueState.setStorageFull(false)
                Log.i(TAG, "Single-track mode: Success for queue ${entry.id}")
            }
            is TrackDownloadOutcome.Failed -> {
                val truncated = outcome.error.take(1000)
                val type = classifier.classify(
                    FailureContext(
                        phase = DownloadPhase.DOWNLOADING,
                        errorText = outcome.error,
                        httpStatus = null,
                        causeChain = emptyList(),
                    ),
                )
                return settleSingleFailure(entry, track, type, truncated)
            }
            is TrackDownloadOutcome.Unmatched -> {
                val err = "No YouTube match for: ${track.artist} - ${track.title}"
                downloadQueueDao.failIfInProgress(
                    id = entry.id,
                    errorMessage = err,
                    completedAt = System.currentTimeMillis(),
                    failureType = DownloadFailureType.NO_MATCH,
                    rejectedVideoId = outcome.rejectedVideoId,
                )
                fileReplacement.restoreKeptFile(entry.trackId, track.filePath)
                Log.w(TAG, "Single-track mode: Unmatched for queue ${entry.id}")
            }
            is TrackDownloadOutcome.Deferred -> {
                // v0.9.17 strict-FLAC: lossless source unavailable and
                // yt-dlp fallback off. The DAO row was already moved to
                // WAITING_FOR_LOSSLESS by TrackDownloaderImpl — leave it
                // alone and return success so WorkManager doesn't reschedule.
                Log.i(
                    TAG,
                    "Single-track mode: Deferred for queue ${entry.id} (lossless source unavailable)",
                )
            }
        }
        return Result.success()
    }

    /**
     * A failed retry, by the one taxonomy. Before, every failure here was FAILED at once, so a
     * Retry tapped on a flaky connection just failed again. Now a transient failure goes back to
     * PENDING and WorkManager retries with backoff ([MAX_ATTEMPTS] per tap); only when those run
     * out, or the failure is for good, does the row show as FAILED with what went wrong. A rate
     * limit queues the same song again in about 15 minutes; a full phone holds it until Resume.
     * None of these uses one of the row's automatic retries (a manual retry never has).
     */
    private suspend fun settleSingleFailure(
        entry: com.stash.core.data.db.entity.DownloadQueueEntity,
        track: com.stash.core.model.Track,
        type: DownloadFailureType,
        message: String,
    ): Result {
        val lane = DownloadFailureClassifier.laneFor(type, entry.retryCount)
        val attemptsLeft = runAttemptCount + 1 < MAX_ATTEMPTS
        return when {
            lane == FailureLane.STORAGE_FULL -> {
                downloadQueueDao.revertToPendingKeepingRetries(entry.id, message, type)
                queueState.setStorageFull(true)
                syncNotificationManager.showStorageFull()
                Log.w(TAG, "Single-track mode: out of space for queue ${entry.id}; held until Resume")
                Result.success()
            }
            lane == FailureLane.RATE_LIMITED -> {
                downloadQueueDao.revertToPendingKeepingRetries(entry.id, message, type)
                val delay = DownloadQueueResumer.RATE_LIMIT_DELAY
                syncNotificationManager.showRateLimited(System.currentTimeMillis() + delay.inWholeMilliseconds)
                runCatching { singleTrackDownloadEnqueuer.enqueueLater(entry.id, delay) }
                    .onFailure { Log.w(TAG, "couldn't queue queue ${entry.id} again after the rate limit", it) }
                Log.w(TAG, "Single-track mode: rate limited for queue ${entry.id}; trying again in $delay")
                Result.success()
            }
            lane == FailureLane.RETRY && attemptsLeft -> {
                downloadQueueDao.revertToPendingKeepingRetries(entry.id, message, type)
                Log.i(TAG, "Single-track mode: transient failure for queue ${entry.id} (type=$type); retrying")
                Result.retry()
            }
            else -> {
                // For good, or out of attempts: show the user what went wrong.
                downloadQueueDao.failIfInProgress(
                    id = entry.id,
                    errorMessage = message,
                    failureType = type,
                    completedAt = System.currentTimeMillis(),
                )
                // Failed for good: a kept file is the track's download again.
                fileReplacement.restoreKeptFile(entry.trackId, track.filePath)
                Log.w(TAG, "Single-track mode: Failed for queue ${entry.id} (type=$type): ${message.take(120)}")
                Result.success()
            }
        }
    }

    /**
     * Hands the post-sync FLAC upgrade sweep to [com.stash.core.data.lossless.FlacUpgradeSweeper]
     * (spec 2026-07-22 §3), which runs it only when the user turned on "Auto-upgrade to
     * FLAC after sync", and never over a batch that is still running or waiting.
     * [FlacUpgradeWorker] owns pacing, rate limiting, per-track status and cancellation.
     *
     * The tracks this run just downloaded are excluded: the download path already tried
     * lossless for them. A failed sweep is logged and the sync still succeeds: the
     * downloads are done, and the next sync or "Check for upgrades" sweeps again.
     */
    private suspend fun runLosslessUpgradeSweep(recentlyDownloadedTrackIds: Set<Long>) {
        try {
            val result = flacUpgradeSweeper.afterSync(justDownloaded = recentlyDownloadedTrackIds)
            Log.i(TAG, "FLAC upgrade sweep: $result")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "FLAC upgrade sweep failed; the sync itself succeeded", e)
        }
    }

    /**
     * Best-effort [setForeground]. On Android 12+ (and stricter on 14/15,
     * which this app targets) calling `setForeground()` re-invokes
     * `startForegroundService()`, which the OS REFUSES with
     * [android.app.ForegroundServiceStartNotAllowedException] when the app is in
     * the background — which it routinely is by the time this worker runs, since
     * the PlaylistFetch + Diff phases ahead of it can take a minute on a large
     * library (screen off / app switched away in the meantime). That exception
     * is only a *notification update* failure, but uncaught it propagated to
     * doWork's top-level catch and FAILED the entire sync with 0 downloads
     * ("sync falls flat"). The actual download work doesn't need the
     * re-promotion — the worker is already executing — so swallow the failure
     * and continue as a normal background job. Mirrors
     * [DiscoveryDownloadWorker.safeUpdateForeground].
     */
    private suspend fun safeSetForeground(info: ForegroundInfo) {
        runCatching { setForeground(info) }
            .onFailure { Log.w(TAG, "setForeground failed (foreground-restricted / backgrounded); continuing without notification update", it) }
    }

    /**
     * Creates [ForegroundInfo] for the ongoing download notification.
     *
     * Overload used when we know the current track counts during the
     * download loop — converts them into a progress fraction and a
     * "Downloading track N of M" body.
     */
    private fun createForegroundInfo(downloaded: Int, total: Int): ForegroundInfo {
        val progress = if (total > 0) {
            val base = 0.25f
            val span = 0.70f
            base + span * (downloaded.toFloat() / total)
        } else {
            -1f // indeterminate
        }
        return createForegroundInfo(
            title = "Syncing playlists",
            text = if (total > 0) "Downloading track $downloaded of $total" else "Preparing downloads…",
            progress = progress,
            pause = true,
        )
    }

    /**
     * Builds a [ForegroundInfo] for the download notification. With [pause] (the sync's and the
     * resume run's downloads) its action is Pause, which keeps the queue, and a tap opens
     * Downloads; without (a single retry), a "Cancel" wired to
     * [WorkManager.createCancelPendingIntent] stops just that retry. [progress] can be negative
     * to request an indeterminate spinner.
     */
    private fun createForegroundInfo(
        title: String,
        text: String,
        progress: Float,
        notificationId: Int = SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
        pause: Boolean = false,
    ): ForegroundInfo {
        val notification = if (pause) {
            syncNotificationManager.buildProgressNotification(
                title = title,
                text = text,
                progress = progress,
                pauseIntent = syncNotificationManager.pauseDownloadsIntent(),
                contentIntent = syncNotificationManager.openDownloadsIntent(),
            )
        } else {
            syncNotificationManager.buildProgressNotification(
                title = title,
                text = text,
                progress = progress,
                cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
        }
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
