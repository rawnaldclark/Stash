package com.stash.feature.record.lastfm

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
import androidx.work.workDataOf
import com.stash.feature.record.R
import com.stash.feature.record.imports.ImportNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs Your record's Last.fm import ([RecordLastfm.run]) as a job, so a whole history (100,000 scrobbles is 500 pages, paced)
 * goes on when the person leaves the screen or the app. A run asked for on screen is a foreground job with a quiet notification
 * saying how far it is; a top-up is quiet and runs as a plain job, unless the person asks to watch it. One at a time: a run
 * asked for while one is finishing waits behind it (`APPEND_OR_REPLACE`), never lost. The person's Stop ([cancel]) ends it for
 * good; a run the system stops, or one the app's death cut short, runs again by itself (WorkManager) and carries on from its
 * mark. The job carries what it was asked for ([LastfmAsk]: the account and the scrobbles' generation).
 */
@HiltWorker
class RecordLastfmWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lastfm: RecordLastfm,
) : CoroutineWorker(context, params) {

    private var foregroundOk = true
    private var foreground = false

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result = coroutineScope {
        val ask = LastfmAsk(
            quiet = inputData.getBoolean(KEY_QUIET, false),
            user = inputData.getString(KEY_USER).orEmpty(),
            generation = inputData.getLong(KEY_GENERATION, -1),
        )
        val quiet = ask.quiet
        if (!quiet) promote(null)
        // The notification follows the run at most once a second (the latest progress wins); a quiet run gets one only once
        // the person asks to watch it
        val updates = Channel<LastfmProgress?>(Channel.CONFLATED)
        val notifier = launch {
            for (p in updates) {
                if (!lastfm.quiet || foreground) promote(p)
                delay(NOTIFY_EVERY_MS)
            }
        }
        try {
            lastfm.run(ask) { updates.trySend(it) }
        } finally {
            notifier.cancel()
        }
        Result.success()
    }

    /** The notification, kept up to date (best effort: a job that can't be promoted carries on as a plain one). */
    private suspend fun promote(p: LastfmProgress?) {
        if (!foregroundOk) return
        try {
            setForeground(foregroundInfo(p))
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Started from the background (the app was gone when the job ran again): it goes on as a plain job
            foregroundOk = false
            Log.w(TAG, "the Last.fm import runs without its notification: ${e.javaClass.simpleName}")
        }
    }

    private fun foregroundInfo(p: LastfmProgress?): ForegroundInfo {
        val context = applicationContext
        ImportNotifications.ensureChannel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val part = p?.takeIf { it.pages > 0 }?.let { (it.page.toLong() * 100 / it.pages).toInt().coerceIn(0, 100) }
        val notification = NotificationCompat.Builder(context, ImportNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.record_lastfm_working_title))
            .setContentText(LastfmText.progressLine(context.resources, p))
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
        private const val NAME = "record-lastfm"
        /**
         * A run asked for while one goes waits behind it. Not `KEEP`: a run asked for after the last one's end but before its job
         * returned went to that finishing job, which never ran it ("Asking Last.fm…" for good).
         */
        internal val POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

        private const val KEY_QUIET = "quiet"
        private const val KEY_USER = "user"
        private const val KEY_GENERATION = "generation"
        private const val NOTIFICATION_ID = 9482
        private const val NOTIFY_EVERY_MS = 1_000L

        /** The run [ask]ed for, after the one finishing if there is one. */
        fun start(context: Context, ask: LastfmAsk) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                POLICY,
                OneTimeWorkRequestBuilder<RecordLastfmWorker>()
                    .setInputData(workDataOf(KEY_QUIET to ask.quiet, KEY_USER to ask.user, KEY_GENERATION to ask.generation))
                    .build(),
            )
        }

        /** The person's Stop: the run going ends, and nothing runs again by itself. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
