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
     * Cancels one download, on purpose: the song stays out of the downloads nobody asks for again
     * (a sync's requeue, a kept playlist's pass, a playlist's Download) until its Retry or a tap
     * ([com.stash.core.data.db.dao.KEPT_OUT_MARK]). Cancelling the last one left also ends a Pause:
     * with nothing queued, "Paused" over an empty queue explained nothing, and the next sync's
     * songs waited for a Resume the user had no reason to tap.
     */
    suspend fun cancel(queueId: Long): Boolean {
        if (!cancelRow(queueId, keptOut = true)) return false
        if (downloadQueueDao.activeIds().isEmpty()) queueState.setPaused(false)
        return true
    }

    /**
     * Clear queue: every waiting and running download is cancelled, and the running
     * ones stop (owner, 2026-10-29), and a Pause ends with them (see [cancel]). Returns
     * how many were cancelled.
     *
     * It stops this attempt, nothing more (owner, 2026-11-09): the songs are simply not
     * downloaded, so a playlist shows it isn't fully downloaded, and its Download, the next
     * sync or a kept playlist's pass queue them again. Before, the songs were kept out like
     * a song cancelled on its own, and a playlist said "Downloaded" with most of it missing.
     */
    suspend fun cancelAll(): Int {
        val cancelled = downloadQueueDao.activeIds().count { cancelRow(it, keptOut = false) }
        queueState.setPaused(false)
        return cancelled
    }

    private suspend fun cancelRow(queueId: Long, keptOut: Boolean): Boolean {
        val changed = downloadQueueDao.markCancelledIfActive(queueId, System.currentTimeMillis(), keptOut)
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
