package com.stash.core.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The download notifications' Pause and "Downloads paused"'s Resume ([SyncNotificationManager]).
 * Not exported: only the app's own PendingIntents reach it. Pause keeps the queue (Clear queue on
 * the Downloads screen is the way to drop it); Resume runs it on any network, as the user's own
 * start.
 *
 * Uses [goAsync] so the coroutine can complete before the system tears the receiver down.
 */
@AndroidEntryPoint
class DownloadControlReceiver : BroadcastReceiver() {

    @Inject lateinit var resumer: DownloadQueueResumer

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_PAUSE && action != ACTION_RESUME) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (action == ACTION_PAUSE) resumer.pause() else resumer.resume(ResumeTrigger.USER)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download $action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.stash.action.PAUSE_DOWNLOADS"
        const val ACTION_RESUME = "com.stash.action.RESUME_DOWNLOADS"
        private const val TAG = "DownloadControl"
    }
}
