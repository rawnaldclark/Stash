package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.library.duplicates.DuplicateCleanup
import com.stash.core.data.library.duplicates.DuplicateCleanupState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Runs the one-time [DuplicateCleanup] after the schema 76 migration, in the background.
 *
 * Once per install: enqueued at startup ([enqueueIfPending]) only until [DuplicateCleanupState]
 * says it ran, as unique work with `KEEP` so a relaunch never starts a second copy. Local work, no
 * constraints; a few seconds of short transactions on a large library.
 *
 * It waits its turn: while a sync's diff, a Stash Mix refresh or a discovery run is going, those
 * hold library rows in memory and would trip over a row merged away under them, so the worker
 * retries a minute later (linearly backed off) instead of starting. An error retries the same way;
 * after [MAX_ATTEMPTS] it gives up until the next app start enqueues it again. A stopped run loses
 * at most its open batch, and the next run picks up what is left.
 */
@HiltWorker
class DuplicateCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cleanup: DuplicateCleanup,
) : CoroutineWorker(appContext, params) {

    /** Whether library writers that keep rows in memory are running now; a test points it at a fake. */
    internal var libraryBusy: suspend () -> Boolean = { libraryWritersRunning(applicationContext) }

    override suspend fun doWork(): Result = try {
        if (libraryBusy()) {
            Log.i(TAG, "a sync or a mix refresh is running; trying again shortly")
            retryOrGiveUp()
        } else {
            cleanup.runIfPending()
            Result.success()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "stopped by an error; the next attempt picks up what is left", e)
        retryOrGiveUp()
    }

    private fun retryOrGiveUp(): Result = if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        private const val TAG = "DuplicateCleanup"

        /** Unique work name; the `_v1` matches [DuplicateCleanupState.RULE_VERSION]. */
        const val WORK_NAME = "duplicate_cleanup_v1"

        /** Attempts per enqueue (busy or failed) before giving up until the next app start. */
        private const val MAX_ATTEMPTS = 20

        /**
         * The workers that read library rows, then write links to them: a sync's diff, the Stash Mix
         * refresh and discovery. WorkManager tags every request with its worker's class name.
         */
        private val LIBRARY_WRITERS = listOf(
            DiffWorker::class.java.name,
            StashMixRefreshWorker::class.java.name,
            StashDiscoveryWorker::class.java.name,
        )

        private fun libraryWritersRunning(context: Context): Boolean {
            val manager = WorkManager.getInstance(context)
            return LIBRARY_WRITERS.any { tag ->
                manager.getWorkInfosByTag(tag).get().any { it.state == WorkInfo.State.RUNNING }
            }
        }

        /**
         * Startup: enqueues the cleanup unless it already ran on this install. `KEEP`, so a relaunch
         * while it waits or runs never starts a second copy.
         *
         * @return true when the work was enqueued (or was already pending).
         */
        suspend fun enqueueIfPending(context: Context, state: DuplicateCleanupState): Boolean {
            if (state.isDone()) return false
            enqueue(context, ExistingWorkPolicy.KEEP)
            return true
        }

        /** Right after a re-arm (a merged backup, which restarts nothing): runs now, or after the run under way. */
        fun enqueueAfterRearm(context: Context) {
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                policy,
                OneTimeWorkRequestBuilder<DuplicateCleanupWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
