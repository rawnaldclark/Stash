package com.stash.core.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-schedules the daily sync alarm after the device reboots.
 *
 * WorkManager persists its own work across reboots, but if the user changes
 * the schedule time in [SyncPreferencesManager] we rely on this receiver to
 * re-apply the correct hour/minute.
 *
 * Uses [goAsync] so the coroutine can complete before the system kills the
 * receiver process.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var syncPreferencesManager: SyncPreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = syncPreferencesManager.preferences.first()
                if (prefs.autoSyncEnabled) {
                    syncScheduler.scheduleDailySync(
                        prefs.syncHour,
                        prefs.syncMinute,
                        wifiOnly = prefs.wifiOnly,
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
