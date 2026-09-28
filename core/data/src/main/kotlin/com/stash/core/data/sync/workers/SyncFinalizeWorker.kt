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
import kotlinx.coroutines.CancellationException

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

        /** Snapshot rows older than this at a finished sync belong to a run that never finished. */
        private const val ORPHAN_SNAPSHOT_AGE_MS = 24L * 60 * 60 * 1000
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
            // And any run that never got here: a sync that failed, was stopped or was
            // replaced keeps its snapshot rows (every playlist's tracks) forever
            // otherwise. Only one sync chain runs at a time, so older rows are orphans.
            // Never fails the sync, which already worked.
            try {
                remoteSnapshotDao.pruneOlderThan(System.currentTimeMillis() - ORPHAN_SNAPSHOT_AGE_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pruning old snapshots failed", e)
            }

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

            // A finished sync may have added artists to the library — re-run
            // the artist-photo backfill so new acts get a photo. KEEP policy
            // lets a pass that was already running finish instead of being
            // restarted from the top; it is a cheap dedupe (observed-names
            // set) when nothing is new.
            ArtistImageBackfillWorker.enqueueAfterSync(applicationContext)

            // Shared mixes (spec §5-6): republish what this phone shares, and pull what it follows.
            com.stash.core.data.share.SharedMixPublishWorker.enqueue(applicationContext)
            com.stash.core.data.share.SharedMixFollowWorker.enqueue(applicationContext, force = true)

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
