package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.model.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Final worker in the sync chain. Updates the sync history record with
 * final counts and status, cleans up ephemeral snapshot data, shows a
 * user-facing summary notification, and transitions [SyncStateManager]
 * to the completed state.
 */
@HiltWorker
class SyncFinalizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncHistoryDao: SyncHistoryDao,
    private val remoteSnapshotDao: RemoteSnapshotDao,
    private val syncStateManager: SyncStateManager,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncFinalizeWorker"
    }

    override suspend fun doWork(): Result {
        val syncId = inputData.getLong(TrackDownloadWorker.KEY_SYNC_ID, -1L)
        val downloaded = inputData.getInt(TrackDownloadWorker.KEY_DOWNLOADED, 0)
        val failed = inputData.getInt(TrackDownloadWorker.KEY_FAILED, 0)

        if (syncId == -1L) {
            syncStateManager.onError("SyncFinalizeWorker: missing sync ID")
            return Result.failure()
        }

        try {
            syncStateManager.onFinalizing()

            // Retrieve the sync record to get playlists_checked for the notification.
            val syncRecord = syncHistoryDao.getById(syncId)
            val playlistsChecked = syncRecord?.playlistsChecked ?: 0
            val newTracksFound = syncRecord?.newTracksFound ?: 0

            // Update the sync history with final status.
            syncHistoryDao.updateSyncResult(
                id = syncId,
                status = SyncState.COMPLETED,
                completedAt = System.currentTimeMillis(),
                playlistsChecked = playlistsChecked,
                newTracksFound = newTracksFound,
                tracksDownloaded = downloaded,
                tracksFailed = failed,
                bytesDownloaded = syncRecord?.bytesDownloaded ?: 0,
            )

            // Clean up snapshot tables for this sync run.
            remoteSnapshotDao.deleteAllSnapshotsBySyncId(syncId)

            // Cancel the ongoing progress notification.
            syncNotificationManager.cancelProgress()

            // Show the summary notification.
            syncNotificationManager.showSummary(
                newTracks = downloaded,
                playlistsChecked = playlistsChecked,
                failed = failed,
            )

            // Transition to completed state.
            syncStateManager.onCompleted()

            // Re-fire the artist-photo backfill so artists a sync just added
            // get avatars without waiting for the next app launch (whose KEEP
            // request would be a no-op after the install-time run succeeded).
            ArtistImageBackfillWorker.enqueueAfterSync(applicationContext)

            Log.i(
                TAG,
                "Sync $syncId complete: $playlistsChecked playlists, " +
                    "$newTracksFound new, $downloaded downloaded, $failed failed",
            )

            return Result.success()
        } catch (e: Exception) {
            // A cancelled run is not a failure — rethrow so it isn't recorded
            // FAILED (keeps cancelled runs out of the #337 triage set).
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Finalize failed", e)
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message,
            )
            syncStateManager.onError("Finalize failed: ${e.message}", e)
            return Result.failure()
        }
    }
}
