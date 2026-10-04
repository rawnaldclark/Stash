package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.FlacUpgradeQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.model.FlacUpgradeStatus
import com.stash.core.model.UpgradeResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Drains the flac_upgrade_queue: one lossless upgrade per PENDING row
 * (spec 2026-07-22 §3). Foreground worker — batches run for hours behind
 * the rate limiters, so it needs the DATA_SYNC promotion and a progress
 * notification with a Cancel action (pattern: sync's progress worker).
 *
 * Rate limiting, credential handling, and captcha-herd safety all live inside
 * [LosslessUpgrader]'s pipeline — this loop adds none of its own pacing.
 *
 * Cancellation: WorkManager cancels the coroutine; the CancellationException
 * handler drops the unprocessed PENDING remainder so a stale batch never
 * self-resumes later, then rethrows (project rule).
 */
@HiltWorker
class FlacUpgradeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val queueDao: FlacUpgradeQueueDao,
    private val trackDao: TrackDao,
    private val losslessUpgrader: LosslessUpgrader,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(text = "Preparing…", progress = -1f)

    override suspend fun doWork(): Result {
        // The preference can change after FlacUpgradeSweeper builds the
        // persisted batch but before WorkManager starts this worker (a paced
        // batch waits hours between runs). Enforce consent again at execution
        // time and discard the unstarted worklist — but ONLY for a sweep (after
        // a sync, or Library Health's "Check for upgrades"). The user's explicit
        // "Upgrade to FLAC" selection (FlacUpgradeEnqueuer, untagged) is a
        // manual override that must run with the master toggle off, same
        // contract as Now Playing's forced single-track upgrade.
        if (inputData.getBoolean(KEY_AUTO_SWEEP, false) && !losslessUpgrader.isLosslessEnabled()) {
            queueDao.clearPending()
            Log.i(TAG, "Lossless disabled in Settings: discarded pending auto-sweep FLAC upgrades")
            return Result.success()
        }

        val autoSweep = inputData.getBoolean(KEY_AUTO_SWEEP, false)
        val pending = queueDao.pendingTrackIds()
        if (pending.isEmpty()) return Result.success()
        val total = queueDao.countAll()
        val alreadyTerminal = total - pending.size

        var upgraded = 0
        var noMatch = 0
        var failed = 0
        var skipped = 0
        try {
            pending.forEachIndexed { index, trackId ->
                val track = trackDao.getById(trackId)?.toDomain()
                if (track == null) {
                    // Track deleted since the snapshot — CASCADE already
                    // dropped the row; the status write is a harmless no-op.
                    queueDao.setStatus(trackId, FlacUpgradeStatus.FAILED)
                    failed++
                } else if (autoSweep && track.matchPickedAt != null) {
                    // The user picked this song's audio after the sweep was
                    // queued (#531). The upgrade would re-run the lossless
                    // lookup that likely chose the wrong recording and write
                    // it over their pick. A batch the user chose still runs.
                    Log.i(TAG, "skipping track $trackId: the user picked its audio")
                    queueDao.setStatus(trackId, FlacUpgradeStatus.NO_MATCH)
                    skipped++
                } else {
                    val status = when (losslessUpgrader.upgradeToLossless(track, sweep = true)) {
                        UpgradeResult.Upgraded -> { upgraded++; FlacUpgradeStatus.DONE }
                        UpgradeResult.NoMatch -> {
                            noMatch++
                            trackDao.setFlacNoMatchAt(trackId, System.currentTimeMillis())
                            FlacUpgradeStatus.NO_MATCH
                        }
                        UpgradeResult.Error -> { failed++; FlacUpgradeStatus.FAILED }
                        // The relay is serving streams first today. Leave this row and the rest
                        // pending and come back later: WorkManager's backoff grows each time, and
                        // each retry costs one catalog search and one unmetered relay answer.
                        UpgradeResult.Paced -> {
                            Log.i(TAG, "relay paced the FLAC upgrade sweep; ${pending.size - index} left, retrying later")
                            return Result.retry()
                        }
                    }
                    queueDao.setStatus(trackId, status)
                }
                val done = alreadyTerminal + index + 1
                safeSetForeground(
                    createForegroundInfo(
                        text = "Upgrading to FLAC · $done/$total",
                        progress = done.toFloat() / total,
                    ),
                )
            }
        } catch (ce: CancellationException) {
            // User hit Cancel (or the system pulled the plug): drop the
            // remainder so the batch doesn't zombie-resume on retry.
            queueDao.clearPending()
            syncNotificationManager.cancelFlacUpgrade()
            throw ce
        }

        syncNotificationManager.showFlacUpgradeSummary(
            upgraded = upgraded, noMatch = noMatch, failed = failed, skipped = skipped,
        )
        return Result.success(
            workDataOf(KEY_UPGRADED to upgraded, KEY_NO_MATCH to noMatch, KEY_FAILED to failed),
        )
    }

    private suspend fun safeSetForeground(info: ForegroundInfo) {
        runCatching { setForeground(info) }
            .onFailure { Log.w(TAG, "setForeground failed; continuing without notification update", it) }
    }

    private fun createForegroundInfo(text: String, progress: Float): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = syncNotificationManager.buildProgressNotification(
            title = "Upgrading to FLAC",
            text = text,
            progress = progress,
            cancelIntent = cancelIntent,
        )
        return ForegroundInfo(
            SyncNotificationManager.NOTIFICATION_ID_FLAC_UPGRADE,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "flac-upgrade-batch"
        const val KEY_UPGRADED = "flac_upgraded"
        const val KEY_NO_MATCH = "flac_no_match"
        const val KEY_FAILED = "flac_failed"

        /**
         * Input-data flag set ONLY by FlacUpgradeSweeper: a library-wide sweep,
         * whether it runs after a sync or from Library Health's "Check for
         * upgrades". Nobody picked those songs one by one, so the consent
         * re-check in [doWork] discards the batch when the master lossless
         * toggle is off, and a song whose audio the user picked since is left
         * alone (#531) — while a hand-picked selection (FlacUpgradeEnqueuer,
         * untagged) runs regardless.
         */
        const val KEY_AUTO_SWEEP = "flac_auto_sweep"
        private const val TAG = "FlacUpgradeWorker"
    }
}
