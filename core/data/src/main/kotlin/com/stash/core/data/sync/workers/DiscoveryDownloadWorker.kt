package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
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
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Drains `download_queue` rows outside any sync (`sync_id IS NULL`): a
 * playlist's Download switch and the songs it gains (#474), a followed mix's
 * "Download this mix", Library Health repairs. Parallels
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
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "discovery_download"
        /** It downloads kept playlists, followed mixes and repairs, not just discoveries (#474). */
        private const val NOTIFICATION_TITLE = "Downloading songs"
        private const val TAG = "DiscoveryDownload"

        /**
         * [policy] REPLACE (the default) restarts the drain now, for a tap.
         * APPEND_OR_REPLACE queues a drain behind the current one instead, so
         * background work never cancels the song in progress or swaps a waiting
         * tap's constraints for its own (#474).
         */
        fun enqueueOneTime(
            context: Context,
            constraints: Constraints,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ): Operation {
            val work = OneTimeWorkRequestBuilder<DiscoveryDownloadWorker>()
                .setConstraints(constraints)
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, work)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return buildForegroundInfo(NOTIFICATION_TITLE, "Preparing\u2026", progress = -1f)
    }

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
        safeUpdateForeground(NOTIFICATION_TITLE, "Preparing\u2026", progress = -1f)

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

        for ((index, queueItem) in pending.withIndex()) {
            safeUpdateForeground(
                title = NOTIFICATION_TITLE,
                text = "${index + 1} of ${pending.size}",
                progress = (index.toFloat() / pending.size),
            )

            try {
                if (processQueueItem(queueItem)) completed++
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
        return Result.success()
    }

    /** Runs one discovery row in its own cancellable child, never the worker Job. */
    private suspend fun processQueueItem(queueItem: DownloadQueueEntity): Boolean = supervisorScope {
        val child = async {
            if (downloadQueueDao.claimForDownload(queueItem.id) == 0) return@async false
            val itemJob = currentCoroutineContext().job
            downloadJobs.register(queueItem.id, itemJob)
            itemJob.invokeOnCompletion { downloadJobs.unregister(queueItem.id, itemJob) }
            if (downloadQueueDao.getStatusById(queueItem.id) == DownloadStatus.SKIPPED) return@async false

            val trackEntity = trackDao.getById(queueItem.trackId) ?: return@async false
            if (blocklistGuard.isBlocked(
                    artist = trackEntity.artist,
                    title = trackEntity.title,
                    spotifyUri = null,
                    youtubeId = null,
                )) {
                downloadQueueDao.deleteByTrackId(trackEntity.id)
                return@async false
            }
            if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                return@async downloadQueueDao.completeIfInProgress(
                    id = queueItem.id,
                    completedAt = System.currentTimeMillis(),
                ) == 1
            }

            val track = trackEntity.toDomain()
            val outcome = try {
                trackDownloader.downloadTrack(track, queueItem.youtubeUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "downloadTrack threw for ${track.artist} - ${track.title}", error)
                TrackDownloadOutcome.Failed(error.message.orEmpty())
            }
            when (outcome) {
                is TrackDownloadOutcome.Success -> {
                    // NonCancellable: cancel() cancels this child, so an unwrapped
                    // read would throw and strand the file that just landed.
                    val cancelledMidFlight = withContext(NonCancellable) {
                        if (downloadQueueDao.getStatusById(queueItem.id) != DownloadStatus.SKIPPED) {
                            false
                        } else {
                            runCatching { File(outcome.filePath).delete() }
                            true
                        }
                    }
                    if (cancelledMidFlight) {
                        false
                    } else {
                        handleSuccess(queueItem, trackEntity, outcome)
                        downloadQueueDao.getStatusById(queueItem.id) == DownloadStatus.COMPLETED
                    }
                }
                is TrackDownloadOutcome.Unmatched -> {
                    handleUnmatched(queueItem, track, outcome)
                    false
                }
                is TrackDownloadOutcome.Failed -> {
                    handleFailed(queueItem, track, outcome)
                    false
                }
                is TrackDownloadOutcome.Deferred -> false
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

        trackDao.markAsDownloaded(
            trackId = trackEntity.id,
            filePath = outcome.filePath,
            fileSizeBytes = fileSize,
            sampleRateHz = meta?.sampleRateHz,
            bitsPerSample = meta?.bitsPerSample,
        )

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

    private suspend fun handleFailed(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Failed,
    ) {
        Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
        downloadQueueDao.incrementRetryCount(queueItem.id)
        downloadQueueDao.failIfInProgress(
            id = queueItem.id,
            failureType = DownloadFailureType.UNKNOWN,
            errorMessage = outcome.error.take(500),
            completedAt = System.currentTimeMillis(),
        )
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
     * helper. Duplicated rather than promoted to a public method on
     * [SyncNotificationManager] because the WorkManager cancel intent is
     * per-worker-instance and SyncNotificationManager is a singleton.
     */
    private fun buildForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
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
