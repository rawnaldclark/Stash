package com.stash.core.data.sync

import android.content.Context
import android.util.Log
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
 * target hour/minute; manual syncs run immediately with relaxed constraints.
 *
 * Manual sync only requires a network connection (any type) so the user's
 * explicit intent is honored. Scheduled syncs may additionally require an
 * unmetered network and sufficient battery.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateManager: SyncStateManager,
) {

    companion object {
        /** Unique work name for the sync chain. Only one chain at a time. */
        const val UNIQUE_WORK_NAME = "stash_daily_sync"
        private const val TAG = "SyncScheduler"
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
     * @param hour     Hour of day (0-23).
     * @param minute   Minute of hour (0-59).
     * @param wifiOnly When true, requires an unmetered (Wi-Fi) network; otherwise
     *                 any network connection suffices.
     */
    fun scheduleDailySync(hour: Int, minute: Int, wifiOnly: Boolean = true) {
        val delayMs = computeDelayToNextSync(hour, minute)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .build()
        enqueueChain(initialDelayMs = delayMs, constraints = constraints)
    }

    /**
     * Triggers a sync immediately without any initial delay.
     *
     * Uses relaxed constraints (any network, no battery requirement) because
     * the user explicitly requested this sync. Replaces any existing pending
     * or running sync chain.
     */
    fun triggerManualSync() {
        Log.i(TAG, "Manual sync triggered by user")
        // Immediately signal the UI that sync is starting so the button
        // shows progress feedback even before WorkManager picks up the work.
        syncStateManager.onAuthenticating()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        enqueueChain(initialDelayMs = 0, constraints = constraints)
    }

    /**
     * Cancels any pending or running sync chain.
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        syncStateManager.reset()
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
     * @param constraints    WorkManager constraints applied to the network-heavy workers
     *                       (Fetch, Diff, Download). The Finalize worker uses no
     *                       constraints since it only writes local state.
     */
    private fun enqueueChain(initialDelayMs: Long, constraints: Constraints) {
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

        Log.d(TAG, "Sync chain enqueued (delay=${initialDelayMs}ms)")
    }
}
