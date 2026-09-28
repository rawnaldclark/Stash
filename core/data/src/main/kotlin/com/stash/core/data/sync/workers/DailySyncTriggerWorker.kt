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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Starts the daily Auto-sync. Runs as unique periodic work
 * ([SyncScheduler.TRIGGER_WORK_NAME]) at the chosen time every day, and starts
 * the sync chain when today is a sync day.
 *
 * Reads the sync settings fresh on every run, so changed days or Wi-Fi-only
 * apply without rescheduling. Does nothing when Auto-sync is off, when the
 * run's day isn't a sync day, or when a sync is already running or queued by
 * "Sync now" ([SyncScheduler.startScheduledSync]).
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

        /**
         * The day of the sync slot a run belongs to. Doze can hold a run past
         * midnight, and a Sunday 23:30 slot run at Monday 00:10 is still Sunday's,
         * so today's slot more than an hour ahead means the run is yesterday's.
         */
        internal fun dueDay(hour: Int, minute: Int, clock: Clock = Clock.systemDefaultZone()): DayOfWeek {
            val now = LocalDateTime.now(clock)
            val slot = now.toLocalDate().atTime(hour, minute)
            return (if (slot.isAfter(now.plusHours(1))) slot.minusDays(1) else slot).dayOfWeek
        }
    }

    override suspend fun doWork(): Result = try {
        val prefs = syncPreferencesManager.preferences.first()
        if (!prefs.autoSyncEnabled) {
            Log.i(TAG, "Auto-sync is off — nothing to do")
        } else {
            val day = dueDay(prefs.syncHour, prefs.syncMinute)
            if (DayOfWeekSet(prefs.syncDays).contains(day)) {
                syncScheduler.startScheduledSync(prefs.wifiOnly)
            } else {
                Log.i(TAG, "$day isn't a sync day — skipping")
            }
            // Pinned only once the sync is queued: if the process dies first, WorkManager
            // reruns this run, and a pin set earlier would push that to tomorrow. Not in
            // a finally either: a run cancelled by a time change would pin the new
            // trigger to the old time.
            syncScheduler.anchorNextTrigger(prefs.syncHour, prefs.syncMinute)
        }
        Result.success()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // Never throw: WorkManager marks a periodic worker that throws FAILED for
        // good (it skips the periodic reset), which would end the daily sync.
        Log.w(TAG, "Daily sync trigger failed, retrying", e)
        Result.retry()
    }
}
