package com.stash.data.cloud.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads the queued songs until none are left that can go ([CloudUploadRun] is the pass itself):
 * "Save to Google Drive" and "Back up my downloads" both end up here.
 *
 * Runs only on the network the Sync tab's Wi-Fi only setting allows (Wi-Fi by default) and as a foreground
 * job with a progress notification, since a big batch takes a while. Losing the network or the
 * process costs little: each song's resumable session and committed offset are on its row, and
 * the next run carries on from there. A run that lost the connection is retried later
 * (WorkManager's backoff).
 */
@HiltWorker
class CloudUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: CloudDao,
    private val uploader: DriveUploader,
    private val settings: CloudUploadSettings,
    private val config: CloudConfig,
) : CoroutineWorker(context, params) {

    private var foregroundOk = true

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Saving to Google Drive", 0, 0)

    override suspend fun doWork(): Result {
        if (!config.isEnabled(CloudProvider.GOOGLE_DRIVE)) return Result.success()
        val run = CloudUploadRun(
            dao = dao,
            settings = settings,
            upload = { row, progress -> uploader.upload(row, progress) },
            onProgress = ::promote,
        )
        return when (run.run()) {
            CloudUploadRun.Outcome.Finished -> Result.success()
            CloudUploadRun.Outcome.RetryLater -> if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
        }
    }

    private suspend fun promote(title: String, left: Int, sent: Long, total: Long) {
        val text = if (left > 1) "$title · $left left" else title
        val percent = if (total > 0) (sent * 100 / total).toInt() else 0
        val info = foregroundInfo(text, percent, if (total > 0) 100 else 0)
        if (!foregroundOk) return
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. a foreground start not allowed from the background: carry on as a plain job.
            foregroundOk = false
            Log.w(TAG, "Couldn't promote to foreground; continuing in the background")
        }
    }

    private fun foregroundInfo(text: String, progress: Int, max: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Google Drive uploads", NotificationManager.IMPORTANCE_LOW))
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Saving to Google Drive")
            .setContentText(text)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "CloudUpload"
        private const val CHANNEL_ID = "cloud_upload"
        private const val NOTIFICATION_ID = 9471

        /** Tries per song before it is FAILED. */
        const val MAX_ATTEMPTS = 3

        /** Retries of a run that keeps losing the connection before it waits for the next kick. */
        private const val MAX_RUNS = 10
    }
}

/** Runs the upload queue: [CloudUploadScheduler] in the app, a fake in tests. */
interface CloudUploadRunner {
    /** Runs the queue (after any run in progress: it may be on its last song already). */
    suspend fun kick()

    /**
     * Stops uploading, returning once WorkManager has taken the stop in (so a [kick] after it starts
     * a new run instead of queueing behind the stopped one). Sessions stay on their rows; no file is
     * touched in Drive.
     */
    suspend fun cancel()

    /** True while an upload run is actually running (false while it waits for Wi-Fi). */
    val running: Flow<Boolean>

    /** True while the network rule is Wi-Fi only (for "Waiting for Wi-Fi"). */
    val wifiOnly: Flow<Boolean>
}

/**
 * Starts [CloudUploadWorker]. Network rule = the Sync tab's Wi-Fi only setting (on by default), like
 * downloads and cloud scans; no setting of its own.
 */
@Singleton
class CloudUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncPreferences: SyncPreferencesManager,
    private val config: CloudConfig,
) : CloudUploadRunner {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * At most one run waits behind a running one; a run that hasn't started is replaced, so it
     * picks up the current network rule (the user may have allowed mobile data since).
     */
    override suspend fun kick() {
        if (!config.isEnabled(CloudProvider.GOOGLE_DRIVE)) return
        val infos = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE).first()
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val waiting = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (running && waiting) return // the run already queued behind it will see the new songs
        val wifiOnly = syncPreferences.preferences.first().wifiOnly
        val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(UNIQUE)
            .build()
        workManager.enqueueUniqueWork(UNIQUE, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun cancel() {
        workManager.cancelUniqueWork(UNIQUE).await()
    }

    override val running: Flow<Boolean>
        get() = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    override val wifiOnly: Flow<Boolean> get() = syncPreferences.preferences.map { it.wifiOnly }

    companion object {
        const val UNIQUE = "cloud_upload"
    }
}
