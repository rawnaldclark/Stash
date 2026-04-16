package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Third worker in the sync chain. Downloads new tracks discovered by [DiffWorker].
 *
 * Promotes itself to a foreground service with an ongoing progress notification
 * so the system does not kill the work during lengthy downloads.
 *
 * Each track is downloaded through the [TrackDownloader] abstraction which
 * delegates to the full yt-dlp pipeline (search, download, tag, organize).
 *
 * Outputs [KEY_SYNC_ID], [KEY_DOWNLOADED], and [KEY_FAILED] counts.
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
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_FAILED = "failed"
        private const val TAG = "TrackDownloadWorker"
    }

    /**
     * Called by WorkManager BEFORE [doWork] runs. Returning a [ForegroundInfo]
     * here signals that this is a long-running worker that needs foreground
     * service promotion, and WorkManager will start the foreground service
     * itself before invoking [doWork]. This bypasses the Android 12+ restriction
     * on starting foreground services from the background, because WorkManager
     * is the one starting the service — not an already-backgrounded app.
     *
     * Without this override, the worker is treated as a regular background job
     * and gets killed within ~10 minutes of the app being backgrounded.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            title = "Syncing playlists",
            text = "Preparing downloads…",
            progress = -1f, // indeterminate until we know the track count
        )
    }

    override suspend fun doWork(): Result {
        val syncId = inputData.getLong(DiffWorker.KEY_SYNC_ID, -1L)
        if (syncId == -1L) {
            syncStateManager.onError("TrackDownloadWorker: missing sync ID")
            return Result.failure()
        }

        try {
            syncHistoryDao.updateStatus(syncId, SyncState.DOWNLOADING)

            // Determine which services are connected so we only retry their tracks.
            val connectedSources = buildList {
                if (tokenManager.isAuthenticated(com.stash.core.auth.model.AuthService.SPOTIFY)) add("SPOTIFY")
                if (tokenManager.isAuthenticated(com.stash.core.auth.model.AuthService.YOUTUBE_MUSIC)) add("YOUTUBE")
                add("BOTH")
            }
            Log.d(TAG, "Connected sources for retry: $connectedSources")

            // Diagnostic: log the actual queue state before any changes
            val statusCounts = downloadQueueDao.getStatusCounts()
            Log.i(TAG, "Queue status breakdown: ${statusCounts.map { "${it.status}=${it.count}" }}")
            val orphanCounts = downloadQueueDao.getOrphanedTrackCounts()
            Log.i(TAG, "Orphaned undownloaded tracks (no active queue entry): ${orphanCounts.map { "${it.source}=${it.cnt}" }}")

            // Reset exhausted retries so tracks get another chance each sync.
            downloadQueueDao.resetExhaustedRetries()

            // Reset stale IN_PROGRESS entries from a previous interrupted run.
            // Safe because this worker is a unique chain — only one runs at a time.
            val resetInProgress = downloadQueueDao.resetStaleInProgress()
            if (resetInProgress > 0) {
                Log.i(TAG, "Reset $resetInProgress stale IN_PROGRESS entries back to PENDING")
            }

            // Re-queue tracks that are undownloaded but have no active queue entry.
            // This catches tracks whose retries were all exhausted and entries cleaned up,
            // or tracks that somehow never got queued.
            val unqueuedTrackIds = downloadQueueDao.getUnqueuedTrackIds(connectedSources)
            if (unqueuedTrackIds.isNotEmpty()) {
                Log.i(TAG, "Re-queuing ${unqueuedTrackIds.size} undownloaded tracks with no active queue entry")
                val newEntries = unqueuedTrackIds.map { trackId ->
                    com.stash.core.data.db.entity.DownloadQueueEntity(
                        trackId = trackId,
                        syncId = syncId,
                    )
                }
                downloadQueueDao.insertAll(newEntries)
            }

            // Collect ALL pending items (from any sync) plus retryable failed items.
            val allPending = if (connectedSources.isNotEmpty()) {
                downloadQueueDao.getAllPendingBySources(connectedSources)
            } else {
                downloadQueueDao.getPendingBySyncId(syncId)
            }
            val retryItems = if (connectedSources.isNotEmpty()) {
                downloadQueueDao.getRetryableBySources(connectedSources)
            } else {
                emptyList()
            }
            // Deduplicate (a track could appear in both lists)
            val seen = mutableSetOf<Long>()
            val pendingItems = (allPending + retryItems).filter { seen.add(it.trackId) }
            val total = pendingItems.size
            Log.d(TAG, "Download queue: ${allPending.size} pending + ${retryItems.size} retry = $total total (deduped)")

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

            // The worker is already running as a foreground service because
            // getForegroundInfo() was overridden and WorkManager promoted us
            // before calling doWork(). This setForeground() call UPDATES the
            // notification to show the real track count now that we know it.
            syncStateManager.onDownloading(downloaded = 0, total = total)
            setForeground(createForegroundInfo(downloaded = 0, total = total))

            // ── Parallel download loop ──────────────────────────────────
            //
            // All pending items are launched as concurrent coroutines. The
            // Semaphore(8) inside DownloadManager.downloadTrack() limits
            // how many yt-dlp processes run simultaneously — most coroutines
            // will be suspended at the semaphore, waiting for a slot.
            //
            // Previously this was a sequential for loop, which meant the
            // Semaphore only ever saw one caller and concurrency was always 1.
            // With this change, 8 downloads run in parallel, giving an ~8x
            // speedup (from ~4 tracks/min to ~32 tracks/min).
            //
            // Thread-safe counters (AtomicInteger/AtomicLong) ensure correct
            // tallies despite concurrent updates from multiple coroutines.
            val downloadedCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val totalBytesDownloaded = AtomicLong(0)
            val firstError = AtomicReference<String?>(null)
            val playlistsChecked = inputData.getInt(DiffWorker.KEY_PLAYLISTS_CHECKED, 0)

            supervisorScope {
                for (queueItem in pendingItems) {
                    launch {
                        try {
                            downloadQueueDao.updateStatus(
                                id = queueItem.id,
                                status = DownloadStatus.IN_PROGRESS,
                            )

                            val trackEntity = trackDao.getById(queueItem.trackId)
                            if (trackEntity == null) {
                                Log.w(TAG, "Track ${queueItem.trackId} not found in DB, skipping")
                                downloadQueueDao.updateStatus(
                                    id = queueItem.id,
                                    status = DownloadStatus.FAILED,
                                    errorMessage = "Track not found in database",
                                )
                                failedCount.incrementAndGet()
                                return@launch
                            }

                            if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                                downloadQueueDao.updateStatus(
                                    id = queueItem.id,
                                    status = DownloadStatus.COMPLETED,
                                    completedAt = System.currentTimeMillis(),
                                )
                                downloadedCount.incrementAndGet()
                                return@launch
                            }

                            val track = trackEntity.toDomain()
                            val outcome = trackDownloader.downloadTrack(
                                track = track,
                                preResolvedUrl = queueItem.youtubeUrl,
                            )

                            when (outcome) {
                                is TrackDownloadOutcome.Success -> {
                                    val fileSize = try {
                                        File(outcome.filePath).length()
                                    } catch (_: Exception) { 0L }

                                    trackDao.markAsDownloaded(
                                        trackId = queueItem.trackId,
                                        filePath = outcome.filePath,
                                        fileSizeBytes = fileSize,
                                    )
                                    downloadQueueDao.updateStatus(
                                        id = queueItem.id,
                                        status = DownloadStatus.COMPLETED,
                                        completedAt = System.currentTimeMillis(),
                                    )
                                    totalBytesDownloaded.addAndGet(fileSize)
                                    downloadedCount.incrementAndGet()
                                }
                                is TrackDownloadOutcome.Unmatched -> {
                                    val err = "No YouTube match for: ${track.artist} - ${track.title}"
                                    Log.w(TAG, err)
                                    downloadQueueDao.incrementRetryCount(queueItem.id)
                                    downloadQueueDao.updateStatus(
                                        id = queueItem.id,
                                        status = DownloadStatus.FAILED,
                                        failureType = DownloadFailureType.NO_MATCH,
                                        errorMessage = err,
                                    )
                                    firstError.compareAndSet(null, err)
                                    failedCount.incrementAndGet()
                                }
                                is TrackDownloadOutcome.Failed -> {
                                    Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
                                    downloadQueueDao.incrementRetryCount(queueItem.id)
                                    downloadQueueDao.updateStatus(
                                        id = queueItem.id,
                                        status = DownloadStatus.FAILED,
                                        failureType = DownloadFailureType.DOWNLOAD_ERROR,
                                        errorMessage = outcome.error.take(500),
                                    )
                                    firstError.compareAndSet(null, outcome.error.take(500))
                                    failedCount.incrementAndGet()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download track ${queueItem.trackId}", e)
                            downloadQueueDao.updateStatus(
                                id = queueItem.id,
                                status = DownloadStatus.FAILED,
                                errorMessage = e.message,
                            )
                            failedCount.incrementAndGet()
                        }

                        // Update progress notification after each completed track.
                        val completed = downloadedCount.get() + failedCount.get()
                        syncStateManager.onDownloading(downloaded = completed, total = total)
                        syncNotificationManager.updateProgress(
                            title = "Syncing playlists",
                            text = "Downloaded $completed of $total",
                            progress = 0.25f + 0.70f * (completed.toFloat() / total),
                        )

                        // Flush sync history tallies every 10 tracks for crash resilience.
                        if (completed % 10 == 0) {
                            syncHistoryDao.updateCounts(
                                id = syncId,
                                playlistsChecked = playlistsChecked,
                                newTracksFound = total,
                                tracksDownloaded = downloadedCount.get(),
                                tracksFailed = failedCount.get(),
                                bytesDownloaded = totalBytesDownloaded.get(),
                            )
                        }
                    }
                }
            }
            // supervisorScope waits for all launched coroutines to complete.

            // Final tally flush.
            val finalDownloaded = downloadedCount.get()
            val finalFailed = failedCount.get()

            syncHistoryDao.updateCounts(
                id = syncId,
                playlistsChecked = playlistsChecked,
                newTracksFound = total,
                tracksDownloaded = finalDownloaded,
                tracksFailed = finalFailed,
                bytesDownloaded = totalBytesDownloaded.get(),
            )

            // Store first download error in sync history for on-device debugging.
            val firstErr = firstError.get()
            if (firstErr != null) {
                val summary = "$finalFailed/$total downloads failed. First error: $firstErr"
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.DOWNLOADING,
                    errorMessage = summary.take(1000),
                )
            }

            return Result.success(
                workDataOf(
                    KEY_SYNC_ID to syncId,
                    KEY_DOWNLOADED to finalDownloaded,
                    KEY_FAILED to finalFailed,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download worker failed", e)
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
        )
    }

    /**
     * Builds a [ForegroundInfo] with a "Cancel" action wired to
     * [WorkManager.createCancelPendingIntent] so the user can abort the
     * sync by tapping the notification action. [progress] can be negative
     * to request an indeterminate spinner.
     */
    private fun createForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = cancelIntent,
        )
        return ForegroundInfo(
            SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
