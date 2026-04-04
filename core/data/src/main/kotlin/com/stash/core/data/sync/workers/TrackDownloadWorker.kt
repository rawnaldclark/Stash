package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
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

            // Promote to foreground with an initial progress notification.
            // This can fail if the app is in the background (Android 12+ restriction).
            syncStateManager.onDownloading(downloaded = 0, total = total)
            try {
                setForeground(createForegroundInfo(downloaded = 0, total = total))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service (app may be in background): ${e.message}")
                // Continue anyway — downloads still work without foreground promotion,
                // they're just more likely to be killed by the system for long sessions.
            }

            var downloadedCount = 0
            var failedCount = 0
            var totalBytesDownloaded = 0L
            var firstError: String? = null

            for ((index, queueItem) in pendingItems.withIndex()) {
                try {
                    // Mark as in-progress.
                    downloadQueueDao.updateStatus(
                        id = queueItem.id,
                        status = DownloadStatus.IN_PROGRESS,
                    )

                    // Resolve the track entity to a domain model for the downloader.
                    val trackEntity = trackDao.getById(queueItem.trackId)
                    if (trackEntity == null) {
                        Log.w(TAG, "Track ${queueItem.trackId} not found in DB, skipping")
                        downloadQueueDao.updateStatus(
                            id = queueItem.id,
                            status = DownloadStatus.FAILED,
                            errorMessage = "Track not found in database",
                        )
                        failedCount++
                        continue
                    }

                    val track = trackEntity.toDomain()

                    // Download through the full pipeline (search -> download -> tag -> organize).
                    val outcome = trackDownloader.downloadTrack(
                        track = track,
                        preResolvedUrl = queueItem.youtubeUrl,
                    )

                    when (outcome) {
                        is TrackDownloadOutcome.Success -> {
                            val fileSize = try {
                                File(outcome.filePath).length()
                            } catch (_: Exception) {
                                0L
                            }

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

                            totalBytesDownloaded += fileSize
                            downloadedCount++
                        }
                        is TrackDownloadOutcome.Unmatched -> {
                            val err = "No YouTube match for: ${track.artist} - ${track.title}"
                            Log.w(TAG, err)
                            downloadQueueDao.incrementRetryCount(queueItem.id)
                            downloadQueueDao.updateStatus(
                                id = queueItem.id,
                                status = DownloadStatus.FAILED,
                                errorMessage = err,
                            )
                            if (firstError == null) firstError = err
                            failedCount++
                        }
                        is TrackDownloadOutcome.Failed -> {
                            Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
                            downloadQueueDao.incrementRetryCount(queueItem.id)
                            downloadQueueDao.updateStatus(
                                id = queueItem.id,
                                status = DownloadStatus.FAILED,
                                errorMessage = outcome.error.take(500),
                            )
                            if (firstError == null) firstError = outcome.error.take(500)
                            failedCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download track ${queueItem.trackId}", e)
                    downloadQueueDao.updateStatus(
                        id = queueItem.id,
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message,
                    )
                    failedCount++
                }

                // Update progress notification.
                val completed = index + 1
                syncStateManager.onDownloading(downloaded = completed, total = total)
                val progressFraction = completed.toFloat() / total
                val overallProgress = 0.25f + 0.70f * progressFraction
                syncNotificationManager.updateProgress(
                    title = "Syncing playlists",
                    text = "Downloading track $completed of $total",
                    progress = overallProgress,
                )
            }

            // Update sync history tallies.
            syncHistoryDao.updateCounts(
                id = syncId,
                playlistsChecked = inputData.getInt(DiffWorker.KEY_PLAYLISTS_CHECKED, 0),
                newTracksFound = total,
                tracksDownloaded = downloadedCount,
                tracksFailed = failedCount,
                bytesDownloaded = totalBytesDownloaded,
            )

            // Store first download error in sync history for on-device debugging.
            if (firstError != null) {
                val summary = "$failedCount/$total downloads failed. First error: $firstError"
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.DOWNLOADING,
                    errorMessage = summary.take(1000),
                )
            }

            return Result.success(
                workDataOf(
                    KEY_SYNC_ID to syncId,
                    KEY_DOWNLOADED to downloadedCount,
                    KEY_FAILED to failedCount,
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
     */
    private fun createForegroundInfo(downloaded: Int, total: Int): ForegroundInfo {
        val progress = if (total > 0) {
            val base = 0.25f
            val span = 0.70f
            base + span * (downloaded.toFloat() / total)
        } else {
            0.25f
        }

        val notification = syncNotificationManager.buildProgressNotification(
            title = "Syncing playlists",
            text = if (total > 0) "Downloading track $downloaded of $total" else "Preparing downloads...",
            progress = progress,
        )
        return ForegroundInfo(
            SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
