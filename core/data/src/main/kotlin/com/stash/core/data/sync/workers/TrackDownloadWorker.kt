package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.model.DownloadStatus
import com.stash.core.model.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Third worker in the sync chain. Downloads new tracks discovered by [DiffWorker].
 *
 * Promotes itself to a foreground service with an ongoing progress notification
 * so the system does not kill the work during lengthy downloads.
 *
 * NOTE: Actual yt-dlp download integration is deferred to Phase 6. For now
 * this worker marks each queued track as COMPLETED to validate the chain.
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

            val pendingItems = downloadQueueDao.getPendingBySyncId(syncId)
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

            // Promote to foreground with an initial progress notification.
            syncStateManager.onDownloading(downloaded = 0, total = total)
            setForeground(createForegroundInfo(downloaded = 0, total = total))

            var downloadedCount = 0
            var failedCount = 0

            for ((index, queueItem) in pendingItems.withIndex()) {
                try {
                    // Mark as in-progress.
                    downloadQueueDao.updateStatus(
                        id = queueItem.id,
                        status = DownloadStatus.IN_PROGRESS,
                    )

                    // TODO Phase 6: Actual yt-dlp download.
                    // For now, mark as completed to validate the worker chain.
                    downloadQueueDao.updateStatus(
                        id = queueItem.id,
                        status = DownloadStatus.COMPLETED,
                        completedAt = System.currentTimeMillis(),
                    )

                    // Mark the track as downloaded in the tracks table.
                    trackDao.getById(queueItem.trackId)?.let { track ->
                        trackDao.update(track.copy(isDownloaded = true))
                    }

                    downloadedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process track ${queueItem.trackId}", e)
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
                bytesDownloaded = 0, // Phase 6 will track actual bytes.
            )

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
        )
    }
}
