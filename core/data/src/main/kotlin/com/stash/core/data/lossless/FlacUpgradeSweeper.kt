package com.stash.core.data.lossless

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.core.data.db.dao.FlacUpgradeQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FlacSweepResult {
    data object StreamingMode : FlacSweepResult
    data object LosslessDisabled : FlacSweepResult
    data object NothingToUpgrade : FlacSweepResult
    data class Queued(val count: Int) : FlacSweepResult
}

/** Populates flac_upgrade_queue with lossy downloads and enqueues [FlacUpgradeWorker]. */
@Singleton
class FlacUpgradeSweeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val flacUpgradeQueueDao: FlacUpgradeQueueDao,
    private val losslessUpgrader: LosslessUpgrader,
    private val streamingPreference: StreamingPreference,
) {
    /**
     * @param exclude  track ids to skip (a sync passes the ones it just downloaded)
     * @param autoSweep true for the post-sync sweep: the worker re-checks the lossless
     *                  master toggle for auto batches only. A manual run passes false.
     */
    suspend fun enqueue(exclude: Set<Long> = emptySet(), autoSweep: Boolean): FlacSweepResult {
        if (streamingPreference.current()) return FlacSweepResult.StreamingMode
        if (!losslessUpgrader.isLosslessEnabled()) return FlacSweepResult.LosslessDisabled

        val candidates = trackDao.getLosslessUpgradeCandidates().filterNot { it.id in exclude }
        if (candidates.isEmpty()) return FlacSweepResult.NothingToUpgrade

        flacUpgradeQueueDao.startBatch(candidates.map { it.id })

        WorkManager.getInstance(context).enqueueUniqueWork(
            FlacUpgradeWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FlacUpgradeWorker>()
                .setInputData(workDataOf(FlacUpgradeWorker.KEY_AUTO_SWEEP to autoSweep))
                .build(),
        )
        Log.i("FlacUpgradeSweeper", "enqueued ${candidates.size} candidate(s) (auto=$autoSweep)")
        return FlacSweepResult.Queued(candidates.size)
    }
}