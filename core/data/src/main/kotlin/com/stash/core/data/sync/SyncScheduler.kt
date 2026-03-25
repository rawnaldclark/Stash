package com.stash.core.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.sync.workers.DiffWorker
import com.stash.core.data.sync.workers.PlaylistFetchWorker
import com.stash.core.data.sync.workers.SyncFinalizeWorker
import com.stash.core.data.sync.workers.TrackDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the WorkManager sync chain: Fetch -> Diff -> Download -> Finalize.
 *
 * All sync work is enqueued as a unique work chain under [UNIQUE_WORK_NAME] so
 * that only one sync can run at a time. Scheduled syncs compute a delay to the
 * target hour/minute; manual syncs run immediately.
 *
 * Constraints require an unmetered network and sufficient battery, matching
 * the expectation that sync involves large downloads.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        /** Unique work name for the sync chain. Only one chain at a time. */
        const val UNIQUE_WORK_NAME = "stash_daily_sync"
    }

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /**
     * Schedules a daily sync to run at the specified [hour] and [minute].
     *
     * Computes the delay until the next occurrence of the target time and
     * enqueues the full worker chain with that initial delay. Uses
     * [ExistingWorkPolicy.REPLACE] so a new schedule replaces any pending one.
     *
     * @param hour   Hour of day (0-23).
     * @param minute Minute of hour (0-59).
     */
    fun scheduleDailySync(hour: Int, minute: Int) {
        val delayMs = computeDelayToNextSync(hour, minute)
        enqueueChain(initialDelayMs = delayMs)
    }

    /**
     * Triggers a sync immediately without any initial delay.
     * Replaces any existing pending or running sync chain.
     */
    fun triggerManualSync() {
        enqueueChain(initialDelayMs = 0)
    }

    /**
     * Cancels any pending or running sync chain.
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Computes the number of milliseconds until the next occurrence of
     * [hour]:[minute].
     *
     * If the target time has already passed today, computes the delay
     * to the same time tomorrow.
     *
     * @param hour   Hour of day (0-23).
     * @param minute Minute of hour (0-59).
     * @return Delay in milliseconds (always positive).
     */
    fun computeDelayToNextSync(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If the target time has already passed today, schedule for tomorrow.
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }

    /**
     * Builds and enqueues the four-worker chain: Fetch -> Diff -> Download -> Finalize.
     *
     * @param initialDelayMs Delay before the first worker (PlaylistFetchWorker) starts.
     */
    private fun enqueueChain(initialDelayMs: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val fetchWork = OneTimeWorkRequestBuilder<PlaylistFetchWorker>()
            .setConstraints(constraints)
            .apply {
                if (initialDelayMs > 0) {
                    setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                }
            }
            .addTag("sync_fetch")
            .build()

        val diffWork = OneTimeWorkRequestBuilder<DiffWorker>()
            .setConstraints(constraints)
            .addTag("sync_diff")
            .build()

        val downloadWork = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .setConstraints(constraints)
            .addTag("sync_download")
            .build()

        val finalizeWork = OneTimeWorkRequestBuilder<SyncFinalizeWorker>()
            .addTag("sync_finalize")
            .build()

        workManager
            .beginUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, fetchWork)
            .then(diffWork)
            .then(downloadWork)
            .then(finalizeWork)
            .enqueue()
    }
}
