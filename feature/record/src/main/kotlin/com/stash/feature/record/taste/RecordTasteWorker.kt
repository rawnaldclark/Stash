package com.stash.feature.record.taste

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.sync.enqueueUniquePeriodicWorkReviving
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Your record's taste worked out again once a day ([RecordTaste.daily]: the same once-a-local-day mark Home and the record use),
 * so the mixes follow this phone's newest plays and the library's changes even on a day neither is opened. Nothing without an
 * import. Quiet: no network, the battery not low.
 */
@HiltWorker
class RecordTasteWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taste: RecordTaste,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        taste.daily()?.join()
        return Result.success()
    }

    companion object {
        private const val NAME = "record-taste-daily"

        /** The daily job (an existing one keeps its time; a finished one is enqueued again). */
        suspend fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<RecordTasteWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWorkReviving(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
