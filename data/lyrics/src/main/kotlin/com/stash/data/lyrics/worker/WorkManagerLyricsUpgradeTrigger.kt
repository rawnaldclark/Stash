package com.stash.data.lyrics.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.data.download.lyrics.LyricsFetchStatus
import com.stash.data.download.lyrics.LyricsUpgradeTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager side of [LyricsUpgradeTrigger]: the user-started lyrics runs of
 * [LyricsTtmlUpgradeWorker] (Settings' re-check, Library Health's fetch), told apart by tag.
 * Bound in `:app` (LyricsUpgradeTriggerModule).
 */
@Singleton
class WorkManagerLyricsUpgradeTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : LyricsUpgradeTrigger {

    override fun enqueueLibraryRecheck() {
        runCatching {
            val request = OneTimeWorkRequestBuilder<LyricsTtmlUpgradeWorker>()
                .setInputData(workDataOf(LyricsTtmlUpgradeWorker.KEY_RECHECK to true))
                .addTag(LyricsTtmlUpgradeWorker.TAG_RECHECK)
                .setConstraints(
                    Constraints.Builder()
                        // ~50-100 KB of TTML per track: keep a whole-library sweep off mobile data.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            // KEEP: a second tap, or a tap while Library Health's run is going, starts nothing new.
            WorkManager.getInstance(context).enqueueUniqueWork(
                LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }.onFailure { Log.w(TAG, "Couldn't enqueue the lyrics re-check", it) }
    }

    override fun cancelLibraryRecheck() {
        // By tag, not unique name: Cancel in Settings never stops a Library Health run.
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(LyricsTtmlUpgradeWorker.TAG_RECHECK) }
    }

    override suspend fun cancelRetiredAutoRun() {
        val wm = WorkManager.getInstance(context)
        // The Flow, not the ListenableFuture API: one read of the current rows, no Guava needed.
        val infos = wm.getWorkInfosForUniqueWorkFlow(LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME).first()
        // The automatic pass never tagged its requests; both user-started entry points do.
        infos.filter { !it.state.isFinished && it.isRetiredAutoRun() }.forEach { info ->
            wm.cancelWorkById(info.id)
            Log.i(TAG, "Cancelled a lyrics upgrade queued by the retired automatic pass (${info.state})")
        }
    }

    override fun enqueueManualFetch() {
        runCatching {
            val request = OneTimeWorkRequestBuilder<LyricsTtmlUpgradeWorker>()
                .setInputData(workDataOf(LyricsTtmlUpgradeWorker.KEY_MANUAL to true))
                .addTag(LyricsTtmlUpgradeWorker.TAG_MANUAL)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    // The user asked for it: any connection will do.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    // Getter, not an initialised property: this singleton can be constructed during Application
    // injection, before WorkManager's Configuration.Provider (Hilt worker factory) is ready.
    override val status: Flow<LyricsFetchStatus>
        get() = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME)
            .map { infos -> infos.lastOrNull { it.state != WorkInfo.State.CANCELLED }.toStatus() }

    override val recheckStatus: Flow<LyricsFetchStatus>
        get() = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME)
            .map { infos ->
                infos.lastOrNull { it.state != WorkInfo.State.CANCELLED && LyricsTtmlUpgradeWorker.TAG_RECHECK in it.tags }
                    .toStatus()
            }

    private fun WorkInfo.isRetiredAutoRun(): Boolean =
        LyricsTtmlUpgradeWorker.TAG_RECHECK !in tags && LyricsTtmlUpgradeWorker.TAG_MANUAL !in tags

    private fun WorkInfo?.toStatus(): LyricsFetchStatus {
        val info = this ?: return LyricsFetchStatus.Idle
        return when (info.state) {
            WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> LyricsFetchStatus.Idle
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> LyricsFetchStatus.Queued
            WorkInfo.State.RUNNING -> LyricsFetchStatus.Running(
                done = info.progress.getInt(LyricsTtmlUpgradeWorker.KEY_DONE, 0),
                total = info.progress.getInt(LyricsTtmlUpgradeWorker.KEY_TOTAL, 0),
            )
            WorkInfo.State.SUCCEEDED -> LyricsFetchStatus.Done(
                upgraded = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_UPGRADED, 0),
                fetched = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FETCHED, 0),
                notFound = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_NOT_FOUND, 0),
                failed = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FAILED, 0),
                bailed = info.outputData.getBoolean(LyricsTtmlUpgradeWorker.OUT_BAILED, false),
            )
        }
    }
}

private const val TAG = "LyricsUpgradeTrigger"
