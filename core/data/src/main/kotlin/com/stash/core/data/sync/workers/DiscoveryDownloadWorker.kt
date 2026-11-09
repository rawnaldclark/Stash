package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.Operation
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.TrackFileReplacement
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.R
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.DownloadQueueState
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.classifier.DownloadFailureClassifier
import com.stash.core.data.sync.classifier.DownloadFailureClassifier.FailureLane
import com.stash.core.data.sync.classifier.DownloadPhase
import com.stash.core.data.sync.classifier.FailureContext
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.Track
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Drains `download_queue` rows outside any sync (`sync_id IS NULL`) that the user
 * asked for: a playlist's Download switch and the songs it gains (#474), a
 * followed mix's "Download this mix", taps, Library Health's "Download N again".
 * Only those: [DownloadQueueDao.pendingDiscoveryDownloads] leaves out leftovers
 * nobody asked for (#532). No Stream-only check, on purpose: those downloads
 * happen in either mode (#474). Parallels
 * [TrackDownloadWorker]'s per-track flow (blocklist guard ->
 * [TrackDownloader.downloadTrack] -> mark COMPLETED with isDownloaded=true +
 * filePath, or FAILED with retry accounting) without the sync-history
 * coupling that worker requires.
 *
 * Started by MusicRepositoryImpl: REPLACE for a tap, a queued-behind start for
 * background work (see its startDiscoveryDrain). [StashDiscoveryWorker] filed
 * these rows until v0.9.37 and started this worker until #474; it does
 * neither now. At the end of the drain, enqueues a one-shot
 * [StashMixRefreshWorker] so mixes re-materialize and the user sees the
 * newly-downloaded survivors without manual refresh.
 *
 * Foreground-service promotion via [getForegroundInfo] is the same
 * pattern TrackDownloadWorker uses — required for long batches that
 * outlive normal background limits.
 */
@HiltWorker
class DiscoveryDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val trackDownloader: TrackDownloader,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val blocklistGuard: BlocklistGuard,
    private val syncNotificationManager: SyncNotificationManager,
    private val downloadJobs: DownloadJobRegistry,
    private val fileReplacement: TrackFileReplacement,
    /** The one failure taxonomy every runner shares (a network blip retries, a source failure is final). */
    private val classifier: DownloadFailureClassifier,
    /** The user's Pause and the out-of-space hold. */
    private val queueState: DownloadQueueState,
    /** An automatic start's constraints, for the retry after YouTube asked us to wait. */
    private val resumer: DownloadQueueResumer,
    /** The songs downloading at once, shared with sync downloads and DownloadManager. */
    private val downloadSlots: com.stash.core.data.sync.DownloadSlots = com.stash.core.data.sync.DownloadSlots(),
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "discovery_download"
        private const val TAG = "DiscoveryDownload"

        /** A drain that left songs for a transient failure retries at most this often per start. */
        const val MAX_ATTEMPTS = 8

        /**
         * [policy] REPLACE (the default) restarts the drain now, for a tap.
         * APPEND_OR_REPLACE queues a drain behind the current one instead, so
         * background work never cancels the song in progress or swaps a waiting
         * tap's constraints for its own (#474). [initialDelay] holds it back
         * (YouTube asked us to wait). A drain that left songs for a network blip
         * retries with exponential backoff from a minute.
         */
        fun enqueueOneTime(
            context: Context,
            constraints: Constraints,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
            initialDelay: kotlin.time.Duration = kotlin.time.Duration.ZERO,
            rateLimitRound: Int = 0,
        ): Operation {
            val work = OneTimeWorkRequestBuilder<DiscoveryDownloadWorker>()
                .setInputData(androidx.work.workDataOf(TrackDownloadWorker.KEY_RATE_LIMIT_ROUND to rateLimitRound))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .apply {
                    if (initialDelay > kotlin.time.Duration.ZERO) {
                        setInitialDelay(initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    }
                }
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, work)
        }
    }

    /** It downloads kept playlists, followed mixes and repairs, not just discoveries (#474). */
    private val notificationTitle: String
        get() = applicationContext.getString(R.string.notif_downloads_title)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return buildForegroundInfo(notificationTitle, applicationContext.getString(R.string.notif_downloads_preparing), progress = -1f)
    }

    /** What one row came to, for the drain's tally and whether it stops. */
    internal enum class ItemResult { COMPLETED, NOTHING, INTERRUPTED, RATE_LIMITED, STORAGE_FULL }

    /**
     * Promote to foreground. Wrapped in runCatching because the JVM unit
     * tests instantiate the worker directly (no WorkManager runtime) — a
     * raw setForeground / WorkManager.getInstance call would throw
     * IllegalStateException there. In production WorkManager invokes
     * [getForegroundInfo] separately before [doWork], so the loop-level
     * updates here are best-effort refreshes and a failed mid-run update
     * doesn't compromise the worker's primary contract (drain the queue,
     * then re-mix).
     */
    private suspend fun safeUpdateForeground(title: String, text: String, progress: Float) {
        runCatching { setForeground(buildForegroundInfo(title, text, progress)) }
            .onFailure { Log.w(TAG, "setForeground failed (likely test env or foreground-restricted): $title / $text", it) }
    }

    override suspend fun doWork(): Result {
        // Paused by the user: nothing downloads on its own until Resume, "Sync now" or a new
        // download tap (which clears the pause before it starts a drain). Checked before the
        // notification, so a paused start shows nothing.
        if (queueState.isPaused()) {
            Log.i(TAG, "downloads are paused; not draining")
            return Result.success()
        }
        safeUpdateForeground(notificationTitle, applicationContext.getString(R.string.notif_downloads_preparing), progress = -1f)

        val pending = downloadQueueDao.pendingDiscoveryDownloads()
        if (pending.isEmpty()) {
            // Empty queue = nothing downloaded this run = no new playable
            // content to materialize. Chaining here was the keystone of the
            // runaway refresh→discovery→download→chainRefresh→… loop: it
            // re-fired the all-recipes mix refresh even when there was
            // nothing to surface. Terminate the chain instead.
            //
            // Recovery is preserved: a leftover/retry row from a prior crashed
            // run leaves `pending` NON-empty, so the drain loop below still
            // runs and re-chains once it completes something.
            Log.d(TAG, "no pending discovery downloads — nothing to drain; not chaining refresh")
            return Result.success()
        }

        Log.i(TAG, "draining ${pending.size} discovery download(s)")

        // Count downloads that produced NEW playable content this run. Only a
        // non-zero count is allowed to chain the mix refresh — an all-FAILED /
        // Unmatched / Deferred / blocked run surfaces nothing new, so chaining
        // would just re-spin the worker cycle for no benefit.
        var completed = 0
        // Songs left PENDING for a transient failure: the drain retries later for them.
        var interrupted = 0
        // A rate limit or a full phone stops the drain: everything after it would fail the same way.
        var stoppedBy: ItemResult? = null

        for ((index, queueItem) in pending.withIndex()) {
            if (stoppedBy != null) break
            safeUpdateForeground(
                title = notificationTitle,
                text = applicationContext.getString(R.string.notif_downloads_progress, index + 1, pending.size),
                progress = (index.toFloat() / pending.size),
            )

            try {
                when (val result = processQueueItem(queueItem)) {
                    ItemResult.COMPLETED -> completed++
                    ItemResult.INTERRUPTED -> interrupted++
                    ItemResult.RATE_LIMITED -> {
                        interrupted++
                        stoppedBy = result
                    }
                    ItemResult.STORAGE_FULL -> stoppedBy = result
                    ItemResult.NOTHING -> Unit
                }
            } catch (cancelled: CancellationException) {
                // NonCancellable around the status read ONLY. The child job is
                // already cancelled, so a bare suspend DAO call throws instead
                // of answering — and a per-item cancel would be indistinguishable
                // from WorkManager stopping the whole worker. The rethrow below
                // still propagates a genuine worker-wide cancellation.
                val userCancelled = withContext(NonCancellable) {
                    val skipped = downloadQueueDao.getStatusById(queueItem.id) == DownloadStatus.SKIPPED
                    // WorkManager stopped the worker (an unmet constraint, the OS
                    // reclaiming the job): hand the claim back so the rerun can take
                    // the row — claimForDownload skips IN_PROGRESS, which stranded
                    // manual downloads (this partition) until the next sync.
                    if (!skipped) downloadQueueDao.releaseClaim(queueItem.id)
                    skipped
                }
                // Either way, a track handed back for a better copy
                // (markForReplacement) that stopped before its row recorded a
                // new file gets its kept file back as its download, or it drops
                // out of the Library. A no-op for every other track.
                fileReplacement.restoreAfterStop(queueItem.trackId)
                if (!userCancelled) {
                    throw cancelled
                }
                Log.i(TAG, "Cancelled discovery queue item ${queueItem.id}")
            }
        }

        // Only chain when this run produced new playable content. An all-
        // FAILED / Unmatched / Deferred / blocked drain surfaces nothing new,
        // so chaining would just re-spin the worker cycle (see the keystone
        // note on the empty-queue early return above).
        if (completed > 0) {
            chainRefresh()
        } else {
            Log.d(TAG, "drained ${pending.size} row(s) but completed 0 — not chaining refresh")
        }

        when (stoppedBy) {
            ItemResult.STORAGE_FULL -> {
                // No automatic retry: the rest stay PENDING, no retry used, until Resume or a cold start.
                Log.w(TAG, "out of space; drain stopped, ${pending.size - completed} song(s) left waiting")
                queueState.setStorageFull(true)
                syncNotificationManager.showStorageFull()
                return Result.success()
            }
            ItemResult.RATE_LIMITED -> {
                // A retry with the usual backoff would ask again within a minute: try again in
                // about 15, as a fresh run queued behind this one, counting the rounds. After
                // MAX_RATE_LIMIT_ROUNDS the songs wait for the next start instead.
                val next = inputData.getInt(TrackDownloadWorker.KEY_RATE_LIMIT_ROUND, 0) + 1
                if (next > TrackDownloadWorker.MAX_RATE_LIMIT_ROUNDS) {
                    Log.w(TAG, "YouTube kept asking us to wait; the songs wait for the next start")
                    return Result.success()
                }
                val delay = DownloadQueueResumer.RATE_LIMIT_DELAY
                Log.w(TAG, "YouTube asked us to wait; next drain in $delay (round $next)")
                syncNotificationManager.showRateLimited(System.currentTimeMillis() + delay.inWholeMilliseconds)
                try {
                    enqueueOneTime(applicationContext, resumer.autoConstraints(), ExistingWorkPolicy.APPEND_OR_REPLACE, delay, next)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't schedule the drain after the rate limit", e)
                }
                return Result.success()
            }
            else -> Unit
        }
        // A song that landed proves there is room again, and that YouTube lets us through.
        if (completed > 0) {
            queueState.setStorageFull(false)
            syncNotificationManager.cancelRateLimited()
        }
        if (interrupted > 0 && runAttemptCount + 1 < MAX_ATTEMPTS) {
            Log.i(TAG, "$interrupted song(s) left for a transient failure; retrying (attempt ${runAttemptCount + 1})")
            return Result.retry()
        }
        return Result.success()
    }

    /** Runs one discovery row in its own cancellable child, never the worker Job. */
    private suspend fun processQueueItem(queueItem: DownloadQueueEntity): ItemResult = supervisorScope {
        // A row is claimed (shown as downloading) only once one of the shared download slots is free.
        val child = async {
            downloadSlots.withSlot {
                val itemJob = currentCoroutineContext().job
                // Claimed and registered as one step: see DownloadJobRegistry.claimAndRegister.
                if (!downloadJobs.claimAndRegister(queueItem.id, itemJob) {
                        downloadQueueDao.claimForDownload(queueItem.id) != 0
                    }
                ) return@async ItemResult.NOTHING
                if (downloadQueueDao.getStatusById(queueItem.id) == DownloadStatus.SKIPPED) {
                    // Cancelled as it was claimed: a replacement keeps its file.
                    fileReplacement.restoreAfterStop(queueItem.trackId)
                    return@async ItemResult.NOTHING
                }

                val trackEntity = trackDao.getById(queueItem.trackId) ?: return@async ItemResult.NOTHING
                if (blocklistGuard.isBlocked(
                        artist = trackEntity.artist,
                        title = trackEntity.title,
                        spotifyUri = null,
                        youtubeId = null,
                    )) {
                    downloadQueueDao.deleteByTrackId(trackEntity.id)
                    return@async ItemResult.NOTHING
                }
                if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                    val done = downloadQueueDao.completeIfInProgress(
                        id = queueItem.id,
                        completedAt = System.currentTimeMillis(),
                    ) == 1
                    return@async if (done) ItemResult.COMPLETED else ItemResult.NOTHING
                }

                val track = trackEntity.toDomain()
                // What the downloader threw, if it threw: its cause chain classifies a network drop.
                var thrown: Exception? = null
                val outcome = try {
                    // The claimed row rides along, so a deferral defers this row only.
                    withContext(com.stash.core.data.sync.ClaimedQueueRow(queueItem.id)) {
                        downloadSlots.holding { trackDownloader.downloadTrack(track, queueItem.youtubeUrl) }
                    }
                } catch (cancelled: CancellationException) {
                    // Re-thrown first, before anything classifies it: a stop is never a failure.
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "downloadTrack threw for ${track.artist} - ${track.title}", error)
                    thrown = error
                    TrackDownloadOutcome.Failed(error.message.orEmpty())
                }
                when (outcome) {
                    is TrackDownloadOutcome.Success -> {
                        // NonCancellable: cancel() cancels this child, so an unwrapped
                        // read would throw and strand the file that just landed.
                        // A new file that replaced the track's own in place is its
                        // only copy and stays (see discardCancelledDownload).
                        val cancelledMidFlight = withContext(NonCancellable) {
                            if (downloadQueueDao.getStatusById(queueItem.id) != DownloadStatus.SKIPPED) {
                                false
                            } else {
                                fileReplacement.discardCancelledDownload(
                                    trackId = trackEntity.id,
                                    keptPath = trackEntity.filePath,
                                    newPath = outcome.filePath,
                                )
                                true
                            }
                        }
                        if (cancelledMidFlight) {
                            ItemResult.NOTHING
                        } else {
                            handleSuccess(queueItem, trackEntity, outcome)
                            if (downloadQueueDao.getStatusById(queueItem.id) == DownloadStatus.COMPLETED) {
                                ItemResult.COMPLETED
                            } else {
                                ItemResult.NOTHING
                            }
                        }
                    }
                    is TrackDownloadOutcome.Unmatched -> {
                        handleUnmatched(queueItem, track, outcome)
                        // The row is FAILED: a file kept for this re-download
                        // (TrackDao.markForReplacement) is the track's download again.
                        fileReplacement.restoreKeptFile(trackEntity.id, trackEntity.filePath)
                        ItemResult.NOTHING
                    }
                    is TrackDownloadOutcome.Failed -> {
                        val result = handleFailed(queueItem, track, outcome, thrown)
                        // Failed for good: a file kept for this re-download is the track's download
                        // again. A row back to PENDING keeps waiting for its new file, as a sync's does.
                        if (result == ItemResult.NOTHING) {
                            fileReplacement.restoreKeptFile(trackEntity.id, trackEntity.filePath)
                        }
                        result
                    }
                    is TrackDownloadOutcome.Deferred -> ItemResult.NOTHING
                }
            }
        }
        child.await()
    }

    private suspend fun handleSuccess(
        queueItem: DownloadQueueEntity,
        trackEntity: TrackEntity,
        outcome: TrackDownloadOutcome.Success,
    ) {
        val fileSize = try { File(outcome.filePath).length() } catch (_: Exception) { 0L }
        val meta = audioDurationExtractor.extract(outcome.filePath)

        val recorded = trackDao.markAsDownloaded(
            trackId = trackEntity.id,
            filePath = outcome.filePath,
            fileSizeBytes = fileSize,
            sampleRateHz = meta?.sampleRateHz,
            bitsPerSample = meta?.bitsPerSample,
        )
        // The track's previous file goes only once the row records the new
        // one: deleted any earlier, a cancel or a dead process in between left
        // the row on a deleted file.
        if (recorded > 0) {
            fileReplacement.deleteReplacedFile(trackEntity.id, trackEntity.filePath, outcome.filePath)
        }

        if (meta != null && meta.format != "unknown") {
            runCatching {
                trackDao.setFormatAndQuality(
                    trackId = trackEntity.id,
                    fileFormat = meta.format,
                    qualityKbps = meta.bitrateKbps,
                )
            }
        }

        // v0.9.21: Discovery downloads start as stubs with duration_ms=0
        // (no Spotify metadata to seed from). Without this fill, every
        // discovery track shows 0:00 in playlist UI and contributes 0 to
        // the header's total-length sum. The sync path's TrackDownloadWorker
        // has its own duration update; this is the discovery equivalent.
        if (meta != null && meta.durationMs > 0) {
            runCatching { trackDao.fillMissingDuration(trackEntity.id, meta.durationMs) }
                .onFailure { Log.w(TAG, "fillMissingDuration failed for ${trackEntity.id}", it) }
        }

        downloadQueueDao.completeIfInProgress(
            id = queueItem.id,
            completedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun handleUnmatched(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Unmatched,
    ) {
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
    }

    /**
     * A failed row, by the one taxonomy ([DownloadFailureClassifier.laneFor]): a source failure
     * (or a transient one that keeps happening) is FAILED and shows in Failed downloads; a
     * network blip, a rate limit or a mid-run sign-in problem goes back to PENDING with a retry
     * used, and the drain retries later; a full phone goes back to PENDING with no retry used.
     * Before, every failure here was FAILED/UNKNOWN, and a kept playlist's song that failed on a
     * blip waited until some unrelated download happened to start a drain.
     */
    private suspend fun handleFailed(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Failed,
        thrown: Exception?,
    ): ItemResult {
        Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
        val type = classifier.classify(
            FailureContext(
                phase = DownloadPhase.DOWNLOADING,
                errorText = outcome.error,
                httpStatus = null,
                causeChain = thrown?.let(DownloadFailureClassifier::causeChainOf).orEmpty(),
            ),
        )
        val message = outcome.error.take(500)
        return when (DownloadFailureClassifier.laneFor(type, queueItem.retryCount)) {
            FailureLane.FINAL -> {
                downloadQueueDao.incrementRetryCount(queueItem.id)
                downloadQueueDao.failIfInProgress(
                    id = queueItem.id,
                    failureType = type,
                    errorMessage = message,
                    completedAt = System.currentTimeMillis(),
                )
                ItemResult.NOTHING
            }
            FailureLane.RETRY -> {
                downloadQueueDao.revertToPendingAfterInterruption(queueItem.id, message, type)
                ItemResult.INTERRUPTED
            }
            FailureLane.RATE_LIMITED -> {
                downloadQueueDao.revertToPendingAfterInterruption(queueItem.id, message, type)
                ItemResult.RATE_LIMITED
            }
            FailureLane.STORAGE_FULL -> {
                downloadQueueDao.revertToPendingKeepingRetries(queueItem.id, message, type)
                ItemResult.STORAGE_FULL
            }
        }
    }

    /**
     * Enqueue the all-recipes mix refresh so newly-downloaded survivors
     * surface without a manual refresh.
     *
     * IMPORTANT: callers MUST gate this on real download progress (a
     * non-empty queue that completed at least one row). Firing it on an
     * idle/no-op run re-spun the runaway refresh→discovery→download→
     * chainRefresh→… worker cycle that re-materialized every mix every
     * ~45s. See the gating in [doWork].
     *
     * runCatching guards JVM unit tests where WorkManager isn't
     * initialized; production paths always succeed. `internal` (not
     * `private`) only so the worker-loop convergence tests can verify it
     * via MockK spyk — body/behaviour is otherwise unchanged.
     */
    internal fun chainRefresh() {
        runCatching { StashMixRefreshWorker.enqueueOneTime(applicationContext) }
            .onFailure { Log.w(TAG, "mix refresh chain failed \u2014 mixes may show stale content until next refresh", it) }
    }

    /**
     * Inline mirror of [TrackDownloadWorker]'s private `createForegroundInfo`
     * helper, under its own notification id ([SyncNotificationManager.NOTIFICATION_ID_DOWNLOADS]).
     */
    private fun buildForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        // Pause, not Cancel: the songs stay queued until Resume (Clear queue drops them).
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            pauseIntent = syncNotificationManager.pauseDownloadsIntent(),
            contentIntent = syncNotificationManager.openDownloadsIntent(),
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                SyncNotificationManager.NOTIFICATION_ID_DOWNLOADS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotificationManager.NOTIFICATION_ID_DOWNLOADS, notification)
        }
    }
}
