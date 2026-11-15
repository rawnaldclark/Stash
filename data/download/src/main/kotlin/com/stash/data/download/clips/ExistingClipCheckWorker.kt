package com.stash.data.download.clips

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Runs the one-time [ExistingClipCheck] in the background.
 *
 * Once per install: startup enqueues it ([enqueueIfPending]) until [ExistingClipCheckState] says it
 * reached the end, as unique work with `KEEP`, so a relaunch never starts a second copy. Low
 * priority: it waits a minute after startup, and only while the battery is not low. Nothing it does
 * needs the network; the downloads it queues carry their own constraints.
 *
 * A stop (the app killed, the battery low) is picked up where it left off: WorkManager runs stopped
 * work again, startup enqueues it again, and the check's cursor skips what is checked. An error
 * retries a minute later (linearly backed off); after [MAX_ATTEMPTS] it waits for the next start.
 */
@HiltWorker
class ExistingClipCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val check: ExistingClipCheck,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        check.runIfPending()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "stopped by an error; the next attempt carries on from the last page checked", e)
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "ExistingClipCheck"

        /** Unique work name. */
        const val WORK_NAME = "existing_clip_check_v1"

        /** Attempts per enqueue before waiting for the next app start. */
        private const val MAX_ATTEMPTS = 5

        /** Out of the way of startup's own work. */
        private const val START_DELAY_SECONDS = 60L

        /**
         * Startup: enqueues the check unless it already ran on this install. `KEEP`, so a relaunch
         * while it waits or runs never starts a second copy.
         *
         * @return true when the work was enqueued (or was already pending).
         */
        suspend fun enqueueIfPending(context: Context, state: ExistingClipCheckState): Boolean {
            if (state.isDone()) return false
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ExistingClipCheckWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .setInitialDelay(START_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                    .build(),
            )
            return true
        }
    }
}
