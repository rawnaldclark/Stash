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
 * Rate limiting, token pools, and captcha-herd safety all live inside
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
        val pending = queueDao.pendingTrackIds()
        if (pending.isEmpty()) return Result.success()
        val total = queueDao.countAll()
        val alreadyTerminal = total - pending.size

        var upgraded = 0
        var noMatch = 0
        var failed = 0
        try {
            pending.forEachIndexed { index, trackId ->
                val track = trackDao.getById(trackId)?.toDomain()
                if (track == null) {
                    // Track deleted since the snapshot — CASCADE already
                    // dropped the row; the status write is a harmless no-op.
                    queueDao.setStatus(trackId, FlacUpgradeStatus.FAILED)
                    failed++
                } else {
                    val status = when (losslessUpgrader.upgradeToLossless(track)) {
                        UpgradeResult.Upgraded -> { upgraded++; FlacUpgradeStatus.DONE }
                        UpgradeResult.NoMatch -> { noMatch++; FlacUpgradeStatus.NO_MATCH }
                        UpgradeResult.Error -> { failed++; FlacUpgradeStatus.FAILED }
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
            upgraded = upgraded, noMatch = noMatch, failed = failed,
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
        private const val TAG = "FlacUpgradeWorker"
    }
}
