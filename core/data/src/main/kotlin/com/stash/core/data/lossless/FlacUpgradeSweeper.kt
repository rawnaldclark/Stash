package com.stash.core.data.lossless

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.stash.core.data.db.dao.FlacUpgradeQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.AutoFlacUpgradePreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What a sweep did, so Library Health can say why nothing happened. */
sealed interface FlacSweepResult {
    /** After a sync with "Auto-upgrade to FLAC after sync" off. */
    data object AutoUpgradeOff : FlacSweepResult
    data object StreamingMode : FlacSweepResult
    data object LosslessDisabled : FlacSweepResult
    data object AlreadyRunning : FlacSweepResult
    data object NothingToUpgrade : FlacSweepResult
    data class Queued(val count: Int) : FlacSweepResult
}

/**
 * The FLAC upgrade sweep: queues every lossy download into flac_upgrade_queue and
 * enqueues [FlacUpgradeWorker] to drain it (spec 2026-07-22 §3). Two callers, one
 * implementation: [afterSync] (TrackDownloadWorker, only when the user opted in) and
 * [runNow] (Library Health's "Check for upgrades").
 */
@Singleton
class FlacUpgradeSweeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val flacUpgradeQueueDao: FlacUpgradeQueueDao,
    private val losslessUpgrader: LosslessUpgrader,
    private val streamingPreference: StreamingPreference,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val autoFlacUpgradePreference: AutoFlacUpgradePreference,
) {
    /** A sync finishing and a tap at the same moment must not both pass the "already running" check. */
    private val mutex = Mutex()

    /**
     * The post-sync sweep. Runs only when the user turned on "Auto-upgrade to FLAC after
     * sync". [justDownloaded] are skipped: the download already tried lossless for them.
     */
    suspend fun afterSync(justDownloaded: Set<Long>): FlacSweepResult {
        if (!autoFlacUpgradePreference.current()) return FlacSweepResult.AutoUpgradeOff
        return sweep(exclude = justDownloaded)
    }

    /** Library Health's "Check for upgrades": the same sweep, on demand. */
    suspend fun runNow(): FlacSweepResult = sweep(exclude = emptySet())

    private suspend fun sweep(exclude: Set<Long>): FlacSweepResult = mutex.withLock {
        if (streamingPreference.current()) return FlacSweepResult.StreamingMode
        if (!losslessUpgrader.isLosslessEnabled()) return FlacSweepResult.LosslessDisabled

        // ENQUEUED counts: a paced worker waits there between retries, and startBatch()
        // would wipe its pending rows. Also protects a batch the user picked themselves.
        val workManager = WorkManager.getInstance(context)
        val active = workManager.getWorkInfosForUniqueWorkFlow(FlacUpgradeWorker.UNIQUE_WORK_NAME).first()
            .any { !it.state.isFinished }
        if (active) return FlacSweepResult.AlreadyRunning

        val retryBefore = System.currentTimeMillis() - NO_MATCH_COOLDOWN_MS
        val candidates = trackDao.getLosslessUpgradeCandidates(retryBefore)
            .filterNot { it.id in exclude }
        if (candidates.isEmpty()) return FlacSweepResult.NothingToUpgrade

        flacUpgradeQueueDao.startBatch(candidates.map { it.id })

        // Same network rule as the Library's "Upgrade to FLAC" batch (FlacUpgradeEnqueuer):
        // a whole-library FLAC run is gigabytes, so the Sync tab's Wi-Fi only setting holds.
        val wifiOnly = syncPreferencesManager.preferences.first().wifiOnly
        workManager.enqueueUniqueWork(
            FlacUpgradeWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FlacUpgradeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                // Both callers are library-wide sweeps, not a hand-picked selection: the
                // worker re-checks the Lossless toggle before every run (a paced batch can
                // wait hours) and leaves alone a song whose audio the user picked since.
                .setInputData(workDataOf(FlacUpgradeWorker.KEY_AUTO_SWEEP to true))
                .build(),
        ).await() // stored before the lock is released, so the next caller sees it ENQUEUED
        Log.i(TAG, "queued ${candidates.size} candidate(s) for a FLAC upgrade")
        FlacSweepResult.Queued(candidates.size)
    }

    private companion object {
        const val TAG = "FlacUpgradeSweeper"

        /** A track with no lossless match is skipped this long before being tried again. */
        const val NO_MATCH_COOLDOWN_MS = 14L * 24 * 60 * 60 * 1000
    }
}
