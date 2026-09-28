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
 * Makes sure Auto-sync has its daily trigger after the device reboots
 * ([SyncScheduler.ensureDailySync]).
 *
 * WorkManager keeps periodic work across reboots, and each run pins the next
 * to the chosen time, so an existing trigger is kept as it is: replacing it
 * would throw away a run that's already due. Changing a sync setting
 * reschedules on its own.
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
                syncScheduler.ensureDailySync(syncPreferencesManager.preferences.first())
            } finally {
                pendingResult.finish()
            }
        }
    }
}
