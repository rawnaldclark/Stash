package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.audio.TuningMeasurer
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.enqueueUniquePeriodicWorkReviving
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Measures the tuning of downloaded songs that have none yet (true tuning, spec 2026-11-21 §3.1), the way
 * [LoudnessBackfillWorker] measures loudness: every 6 hours while charging, idle and not low on battery, up to
 * [BATCH_SIZE] songs or [MAX_RUN_MS] a run, paging by id past rows it can't open (a missing file, a `content://`
 * path). Those stay unstamped, so a re-download is still measured.
 *
 * A file that can't be decoded is stamped without a reading and not tried again; a song with no steady pitch is
 * stored as measured with its low confidence.
 */
@HiltWorker
open class TuningBackfillWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val measurer: TuningMeasurer,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        var page = trackDao.tracksNeedingTuning(afterId = 0L, limit = BATCH_SIZE)
        var measured = 0
        var skipped = 0
        paging@ while (page.isNotEmpty()) {
            for (track in page) {
                if (measured >= BATCH_SIZE || shouldStop() || System.currentTimeMillis() > deadline) break@paging
                val file = track.filePath?.let { File(it) }?.takeIf { it.exists() }
                if (file == null) {
                    skipped++
                    continue
                }
                when (val r = measurer.measure(file)) {
                    is TuningMeasurer.Result.Measured ->
                        trackDao.updateTuning(track.id, r.cents, r.confidence, System.currentTimeMillis())
                    is TuningMeasurer.Result.Failed -> {
                        Log.w(TAG, "tuning not measured for track ${track.id}: ${r.reason}")
                        trackDao.markTuningFailed(track.id, System.currentTimeMillis())
                    }
                }
                measured++
            }
            if (page.size < BATCH_SIZE) break
            page = trackDao.tracksNeedingTuning(afterId = page.last().id, limit = BATCH_SIZE)
        }
        Log.i(TAG, "TuningBackfill: measured $measured, skipped $skipped it couldn't open")
        return Result.success()
    }

    /** WorkManager's `isStopped` is final: this is the seam a test stops a run through. */
    protected open fun shouldStop(): Boolean = isStopped

    companion object {
        private const val TAG = "TuningBackfill"
        const val WORK_NAME = "tuning-backfill"
        private const val BATCH_SIZE = 250

        /** A little under WorkManager's 10-minute cap, so a run ends cleanly. */
        private const val MAX_RUN_MS = 9L * 60 * 1000

        /** Idempotent (KEEP): safe on every cold start. A finished job is enqueued again. */
        suspend fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<TuningBackfillWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWorkReviving(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
