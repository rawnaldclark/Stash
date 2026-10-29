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
) {
    suspend fun cancel(queueId: Long): Boolean {
        val changed = downloadQueueDao.markCancelledIfActive(queueId, System.currentTimeMillis())
        if (changed == 0) return false
        jobs.cancel(queueId)
        WorkManager.getInstance(context)
            .cancelUniqueWork(SingleTrackDownloadEnqueuer.workName(queueId))
        return true
    }

    /**
     * Clear queue: every waiting and running download is cancelled, and the running
     * ones stop (owner, 2026-10-29). Returns how many were cancelled.
     */
    suspend fun cancelAll(): Int = downloadQueueDao.activeIds().count { cancel(it) }

    /**
     * Rows marked downloading with no job behind them (the app was killed mid-download)
     * go back to waiting, so they don't show as downloading until the next sync. Held
     * against claims, so a download starting now can't be caught between its claim and
     * its registration (DownloadJobRegistry.claimAndRegister).
     */
    suspend fun releaseDeadClaims(): Int = jobs.withClaimsHeld {
        downloadQueueDao.inProgressIds().filterNot(jobs::isLive).sumOf { downloadQueueDao.releaseClaim(it) }
    }
}
