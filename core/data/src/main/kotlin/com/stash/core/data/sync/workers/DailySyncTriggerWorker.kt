package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Starts the daily Auto-sync. Runs as unique periodic work
 * ([SyncScheduler.TRIGGER_WORK_NAME]) at the chosen time every day, and starts
 * the sync chain when today is a sync day.
 *
 * Reads the sync settings fresh on every run, so changed days or Wi-Fi-only
 * apply without rescheduling. Does nothing when Auto-sync is off, when today
 * isn't a sync day, or when a sync is already queued or running: it never
 * cancels a sync.
 */
@HiltWorker
class DailySyncTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val syncScheduler: SyncScheduler,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DailySyncTrigger"
    }

    override suspend fun doWork(): Result {
        val prefs = syncPreferencesManager.preferences.first()
        if (!prefs.autoSyncEnabled) {
            Log.i(TAG, "Auto-sync is off — nothing to do")
            return Result.success()
        }
        syncScheduler.anchorNextTrigger(prefs.syncHour, prefs.syncMinute)

        // ponytail: the day is read when the run starts, so a run Doze held past
        // midnight counts as the next day, and a one-day schedule can miss that
        // week. Derive the due day from the last hour:minute before now if that
        // ever matters.
        val today = LocalDate.now().dayOfWeek
        if (!DayOfWeekSet(prefs.syncDays).contains(today)) {
            Log.i(TAG, "$today isn't a sync day — skipping")
            return Result.success()
        }
        syncScheduler.startScheduledSync(prefs.wifiOnly)
        return Result.success()
    }
}
