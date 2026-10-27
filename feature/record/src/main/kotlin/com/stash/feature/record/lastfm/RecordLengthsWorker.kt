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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs Your record's lengths pass ([RecordLengths.run]) as a plain job: quiet, with no notification, whenever the phone is
 * online. A long history is thousands of songs, paced: a job the system stops (its ten minutes are up, the network went, the app
 * died) runs again by itself and plans afresh, since timed songs drop out of the plan and the misses are kept. One at a time: a
 * pass asked for while one runs leaves it running (`KEEP`, the web's `timeSongs` returning the pass under way); the writers that
 * could add songs to time stop it first ([RecordLengths.pause]) and ask for a new one after.
 */
@HiltWorker
class RecordLengthsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lengths: RecordLengths,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        lengths.run()
        return Result.success()
    }

    companion object {
        private const val NAME = "record-lengths"

        /** A pass, unless one is queued or running. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecordLengthsWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /** The pass ends, queued or running, and nothing runs again by itself. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
