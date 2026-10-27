package com.stash.feature.record.lastfm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Runs Your record's lengths pass ([RecordLengths.run]) as a plain job: quiet, with no notification, on an unmetered network (a
 * whole history is thousands of lookups). A job the system stops (its ten minutes are up, the network went, the app died) runs
 * again by itself and plans afresh, since timed songs drop out of the plan and the misses are kept. One at a time: a pass asked
 * for while one runs leaves it running (`KEEP`, the web's `timeSongs` returning the pass under way); the writers that could add
 * songs to time stop it first ([RecordLengths.pause]) and ask for a new one after.
 *
 * A run held by a limit (the day's lookups spent, a rate limit) ends and comes back at the time it lifts: a second, waiting job
 * ([wakeAt]) whose only work is to ask for a pass then.
 */
@HiltWorker
class RecordLengthsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lengths: RecordLengths,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_WAKE, false)) start(applicationContext) else lengths.run()
        return Result.success()
    }

    companion object {
        private const val NAME = "record-lengths"
        private const val WAKE = "record-lengths-wake"
        private const val KEY_WAKE = "wake"

        /** A pass, unless one is queued or running. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecordLengthsWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .build(),
            )
        }

        /** A pass asked for at [atMs] (epoch ms), replacing any wake-up asked for before. */
        fun wakeAt(context: Context, atMs: Long) {
            val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WAKE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RecordLengthsWorker>()
                    .setInputData(workDataOf(KEY_WAKE to true))
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build(),
            )
        }

        /** The pass ends, queued, running or waiting to come back, and nothing runs again by itself. */
        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(NAME)
            work.cancelUniqueWork(WAKE)
        }
    }
}
