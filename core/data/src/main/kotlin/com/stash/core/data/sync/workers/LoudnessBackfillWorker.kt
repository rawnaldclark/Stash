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
import androidx.work.workDataOf
import com.stash.core.data.audio.LoudnessMeasurer
import com.stash.core.data.audio.LoudnessProgressStore
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.enqueueUniquePeriodicWorkReviving
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that drains `tracks.loudness_measured_at IS NULL`
 * rows in batches of [BATCH_SIZE], invoking [LoudnessMeasurer] to compute
 * integrated LUFS + true-peak via ffmpeg's `ebur128` filter and writing the
 * results back through [TrackDao.updateLoudness] / [TrackDao.markLoudnessFailed].
 *
 * ## Scheduling
 *
 * Runs every 6 hours under WorkManager's standard constraint suite — charging
 * + idle + battery-not-low. Measuring 20 lossless tracks burns ~30s of CPU
 * and decodes hundreds of MB of audio; we are emphatically not running this
 * while the user is on battery or actively using the device. The 6-hour
 * cadence is a compromise: long enough that even a million-track library
 * drains in a few weeks of overnight charging sessions, short enough that
 * a freshly-imported batch on a Sunday afternoon doesn't sit unmeasured
 * until Wednesday.
 *
 * ## Batching strategy
 *
 * Each invocation processes up to [BATCH_SIZE] rows and exits — we
 * deliberately don't loop until the queue is empty because:
 *  1. WorkManager has a 10-minute soft cap on a single worker run, and
 *     measuring lossless audio can run 1-2s per track on cold cache.
 *  2. Bounded batches give the OS a chance to reclaim ffmpeg memory
 *     between runs (the bridge spawns a fresh native process each call,
 *     but the JNI surface area is large enough that long-running batches
 *     leak Dalvik refs in the wild).
 *  3. The periodic schedule means we'll be back in 6 hours anyway. There
 *     is no scenario where draining a 10,000-track library in one shot
 *     beats draining it across 500 idle-overnight runs.
 *
 * ## Cancellation
 *
 * `isStopped` is checked at the top of each iteration — a track caught
 * mid-measurement when WorkManager kills the worker is **not** marked
 * failed. The row stays NULL and the next run picks it up. Idempotent.
 *
 * ## Progress reporting
 *
 * After each batch, [LoudnessProgressStore.recordBatchComplete] is called
 * with the actual count processed (which may be less than the batch size
 * if cancelled mid-flight). The EqualizerScreen progress card reads from
 * the same store via a Flow, so the UI stays in sync without any extra
 * plumbing.
 */
@HiltWorker
open class LoudnessBackfillWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val measurer: LoudnessMeasurer,
    private val progressStore: LoudnessProgressStore,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        val batch = trackDao.tracksNeedingLoudness(limit = BATCH_SIZE)
        if (batch.isEmpty()) {
            progressStore.setRemaining(0)
            Log.i(TAG, "LoudnessBackfill: nothing to do")
            return Result.success(workDataOf(KEY_DONE to true))
        }
        Log.i(TAG, "LoudnessBackfill: ${batch.size} tracks to measure")
        var batchCompleted = 0
        for (track in batch) {
            if (shouldStop() || System.currentTimeMillis() > deadline) break
            val path = track.filePath ?: continue
            val file = File(path).takeIf { it.exists() } ?: continue
            when (val r = measurer.measure(file)) {
                is LoudnessMeasurer.Result.Success ->
                    trackDao.updateLoudness(
                        id = track.id,
                        lufs = r.lufs,
                        peak = r.truePeakDbfs,
                        now = System.currentTimeMillis(),
                    )
                is LoudnessMeasurer.Result.Failed -> {
                    Log.w(TAG, "LoudnessBackfill: measurement failed for trackId=${track.id}: ${r.reason}")
                    trackDao.markLoudnessFailed(
                        id = track.id,
                        now = System.currentTimeMillis(),
                    )
                }
            }
            batchCompleted++
            onTrackProcessed()
        }
        progressStore.recordBatchComplete(
            completed = batchCompleted,
            at = System.currentTimeMillis(),
        )
        Log.i(TAG, "LoudnessBackfill complete: processed $batchCompleted/${batch.size}")
        return Result.success()
    }

    /**
     * Test seam — invoked once per track after the row is written back.
     * Production builds do nothing; the test subclass uses it to drive
     * its own iteration counter so [shouldStop] can fire after N tracks.
     */
    protected open fun onTrackProcessed() {
        // no-op in production
    }

    /**
     * Cancellation predicate. Wraps WorkManager's `isStopped` so the test
     * subclass can simulate mid-batch cancellation — `isStopped` itself
     * is a `final` accessor on [androidx.work.ListenableWorker] and can't
     * be overridden directly.
     */
    protected open fun shouldStop(): Boolean = isStopped

    companion object {
        private const val TAG = "LoudnessBackfill"

        /** Unique work name — KEEP-policy enqueue de-dupes across cold starts. */
        const val WORK_NAME = "loudness-backfill"

        /** Output-data key signalling an empty queue (nothing left to measure). */
        const val KEY_DONE = "done"

        /**
         * Max tracks measured per worker run — see class kdoc.
         *
         * Raised from 20 once downloads stopped measuring on battery: this
         * worker is now the primary path, not a mop-up for the odd missed
         * row, so a 500-track sync has to be able to drain. The real limiter
         * is [MAX_RUN_MS], and every run is already charging + device-idle,
         * so a bigger batch costs the user nothing — where 20/run meant a
         * heavy sync took days of charge cycles to level-match.
         */
        private const val BATCH_SIZE = 250

        /**
         * Soft per-run budget. WorkManager itself enforces a 10-minute hard
         * cap on foreground workers; we exit cleanly a hair earlier so the
         * batch-complete bookkeeping always lands instead of dying mid-write.
         */
        private const val MAX_RUN_MS = 10L * 60 * 1000

        /**
         * Schedule the 6-hour periodic backfill. Idempotent — uses
         * [ExistingPeriodicWorkPolicy.KEEP] so re-calls (e.g. on every cold
         * start from `StashApplication.onCreate`) don't reset the next-run
         * timer or clobber an in-flight execution. A finished (e.g. FAILED)
         * job is enqueued again.
         */
        suspend fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<LoudnessBackfillWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
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
