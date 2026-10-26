package com.stash.feature.record.imports

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.feature.record.R
import com.stash.feature.record.importer.ImportProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs Your record's import steps ([RecordImports.runNext]) as a foreground job, so an import goes on when the person leaves the
 * screen or the app, with a quiet notification saying how far it is. Why WorkManager: it keeps the work alive in the
 * background, runs it again after the app is killed mid-write (the commit then carries on from the copies it kept), and is how
 * this app's other long jobs run (downloads, Google Drive uploads). One at a time: a step asked for while another runs waits
 * behind it.
 */
@HiltWorker
class RecordImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val imports: RecordImports,
) : CoroutineWorker(context, params) {

    private var foregroundOk = true

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result = coroutineScope {
        promote(null)
        // The notification follows the import at most once a second (the latest progress wins)
        val updates = Channel<ImportProgress>(Channel.CONFLATED)
        val notifier = launch {
            for (p in updates) {
                promote(p)
                delay(NOTIFY_EVERY_MS)
            }
        }
        try {
            imports.runNext { updates.trySend(it) }
        } finally {
            notifier.cancel()
        }
        Result.success()
    }

    /** The notification, kept up to date (best effort: a job that can't be promoted carries on as a plain one). */
    private suspend fun promote(p: ImportProgress?) {
        if (!foregroundOk) return
        try {
            setForeground(foregroundInfo(p))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Started from the background (the app was gone when a commit resumed): it goes on as a plain job
            foregroundOk = false
            Log.w(TAG, "the import runs without its notification: ${e.javaClass.simpleName}")
        }
    }

    private fun foregroundInfo(p: ImportProgress?): ForegroundInfo {
        val context = applicationContext
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.record_import_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val part = (p as? ImportProgress.Read)?.part?.let { (it * 100).toInt() }
            ?: (p as? ImportProgress.Write)?.takeIf { it.total > 0 }?.let { it.done * 100 / it.total }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.record_import_working_title))
            .setContentText(ImportText.progressLine(context.resources, p ?: ImportProgress.Open))
            .setProgress(100, part ?: 0, part == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "YourRecord"
        private const val NAME = "record-import"
        private const val CHANNEL_ID = "record_import"
        private const val NOTIFICATION_ID = 9481
        private const val NOTIFY_EVERY_MS = 1_000L

        /** Runs the next step, after the one running now if there is one. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RecordImportWorker>().build(),
            )
        }

        /** A step is queued or running. */
        suspend fun alive(context: Context): Boolean =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).first().any { !it.state.isFinished }
    }
}
