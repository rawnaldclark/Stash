package com.stash.data.download.files

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.sync.SyncNotificationManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The copy of "Send to computer" ([SendToComputerCoordinator.copy]) as foreground work: it shows its progress in a
 * notification and holds the processor awake, so Android neither freezes it when the app leaves the screen nor lets
 * the phone sleep through it. One at a time (unique work); stopped by [SendToComputerCoordinator.cancel] or the
 * notification's Cancel. Android restarting it (the process died) carries the send on where it was.
 */
@HiltWorker
class SendToComputerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SendToComputerCoordinator,
    private val notifications: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 0)

    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Result.failure()
        // Refused when the app is not on screen (Android 12+): the copy still runs, as ordinary work.
        foreground(0, 0)
        val power = applicationContext.getSystemService(PowerManager::class.java)
        val awake = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stash:send-to-computer")?.apply { setReferenceCounted(false) }
        try {
            awake?.acquire(AWAKE_AT_MOST_MS)
            coordinator.copy { done, total -> foreground(done, total) }
        } finally {
            runCatching { awake?.release() }
        }
        return Result.success()
    }

    private suspend fun foreground(done: Int, total: Int) {
        runCatching { setForeground(info(done, total)) }
            .onFailure { Log.w(TAG, "the progress notification was not shown: ${it.javaClass.simpleName}") }
    }

    private fun info(done: Int, total: Int): ForegroundInfo {
        val notification = notifications.buildProgressNotification(
            title = "Send to computer",
            text = if (total > 0) "Getting songs ready: ${(done + 1).coerceAtMost(total)} of $total" else "Getting songs ready…",
            progress = if (total > 0) done.toFloat() / total else -1f,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "SendToComputer"
        const val NOTIFICATION_ID = 9012
        /** Tens of gigabytes take a while; the lock is let go when the copy ends, and never held longer than this. */
        const val AWAKE_AT_MOST_MS = 3 * 60 * 60_000L
    }
}
