package com.stash.core.data.sync

import android.content.Context
import androidx.work.WorkManager
import com.stash.core.data.db.dao.DownloadQueueDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Makes cancellation durable before stopping any best-effort runtime work. */
@Singleton
class DownloadCancellationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadQueueDao: DownloadQueueDao,
    private val jobs: DownloadJobRegistry,
    private val queueState: DownloadQueueState,
) {
    /**
     * Cancels one download. Cancelling the last one left also ends a Pause: with nothing queued,
     * "Paused" over an empty queue explained nothing, and the next sync's songs waited for a
     * Resume the user had no reason to tap.
     */
    suspend fun cancel(queueId: Long): Boolean {
        if (!cancelRow(queueId)) return false
        if (downloadQueueDao.activeIds().isEmpty()) queueState.setPaused(false)
        return true
    }

    /**
     * Clear queue: every waiting and running download is cancelled, and the running
     * ones stop (owner, 2026-10-29), and a Pause ends with them (see [cancel]). Returns
     * how many were cancelled.
     */
    suspend fun cancelAll(): Int {
        val cancelled = downloadQueueDao.activeIds().count { cancelRow(it) }
        queueState.setPaused(false)
        return cancelled
    }

    private suspend fun cancelRow(queueId: Long): Boolean {
        val changed = downloadQueueDao.markCancelledIfActive(queueId, System.currentTimeMillis())
        if (changed == 0) return false
        jobs.cancel(queueId)
        WorkManager.getInstance(context)
            .cancelUniqueWork(SingleTrackDownloadEnqueuer.workName(queueId))
        return true
    }

    /**
     * Rows marked downloading with no job behind them (the app was killed mid-download)
     * go back to waiting, so they don't show as downloading until the next sync. Held
     * against claims, so a download starting now can't be caught between its claim and
     * its registration (DownloadJobRegistry.claimAndRegister).
     */
    suspend fun releaseDeadClaims(): Int = jobs.releaseDeadClaims(downloadQueueDao)
}
