package com.stash.data.download.ytdlp

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that keeps the yt-dlp binary up to date.
 *
 * Scheduled to run every 24 hours while the device has network connectivity.
 * On failure the job is retried with WorkManager's default back-off policy.
 */
@HiltWorker
class YtDlpUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ytDlpManager: YtDlpManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ytDlpManager.initialize()
        val result = ytDlpManager.updateYtDlp()
        return when (result) {
            is YtDlpManager.UpdateResult.Failed -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ytdlp_update"

        /**
         * Enqueues a periodic update job that runs every 24 hours.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling is idempotent.
         */
        fun schedulePeriodicUpdate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
