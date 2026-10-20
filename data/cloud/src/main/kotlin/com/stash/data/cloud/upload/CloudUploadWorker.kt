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
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudConfig
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudAuthRequiredException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads the queued songs (`cloud_uploads` rows in QUEUED), oldest first, one at a time, until
 * none are left: "Save to Google Drive" and "Back up my downloads" both end up here.
 *
 * Runs only on the network the Sync tab's Wi-Fi only setting allows (Wi-Fi by default) and as a foreground
 * job with a progress notification, since a big batch takes a while. Losing the network or the
 * process costs little: each song's resumable session and committed offset are on its row, and
 * the next run carries on from there.
 *
 *  - Drive full: backup pauses ([CloudUploadSettings.driveFull]); the songs stay queued and the
 *    card says "Your Google Drive is full" until the user tries again.
 *  - Signed out: the songs stay queued; the card shows the usual Reconnect, which starts the
 *    uploads again ([CloudUploads.resume]).
 *  - No connection / Drive asked to slow down: retry later (WorkManager's backoff).
 *  - Anything wrong with one song: that song goes to the back of the queue; after
 *    [MAX_ATTEMPTS] tries it is FAILED and the others carry on.
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
    private var done = 0

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Saving to Google Drive", 0, 0)

    override suspend fun doWork(): Result {
        if (!config.isEnabled(CloudProvider.GOOGLE_DRIVE)) return Result.success()
        if (settings.driveFull.value) return Result.success()
        while (true) {
            val row = dao.nextQueuedUpload() ?: break
            val left = dao.countQueuedUploads()
            val title = dao.getTrack(row.trackId)?.title ?: "a song"
            promote(title, left, 0, 0)
            try {
                when (val r = uploader.upload(row) { sent, total -> promote(title, left, sent, total) }) {
                    is UploadResult.Saved -> {
                        dao.finishUpload(row.trackId, CloudUploadState.DONE.name, r.fileId, null, now())
                        done++
                    }
                    is UploadResult.Skipped -> dao.finishUpload(row.trackId, CloudUploadState.SKIPPED.name, null, r.reason, now())
                }
            } catch (e: CancellationException) {
                // Wi-Fi lost, or cancelled: the session and offset are saved; the next run resumes.
                throw e
            } catch (e: DriveFullException) {
                Log.i(TAG, "Google Drive is full: backup paused")
                settings.setDriveFull(true)
                return Result.success()
            } catch (e: CloudAuthRequiredException) {
                Log.i(TAG, "Google Drive needs a reconnect: uploads wait")
                return Result.success()
            } catch (e: LocalFileException) {
                Log.w(TAG, "one song's file couldn't be read: ${e.cause?.javaClass?.simpleName ?: "it got shorter"}")
                dao.failUploadAttempt(row.trackId, "Couldn't read this song's file.", MAX_ATTEMPTS, now())
            } catch (e: CloudApiException) {
                Log.w(TAG, "upload of one song failed: HTTP ${e.status}")
                dao.failUploadAttempt(row.trackId, "Google Drive didn't take this song (error ${e.status}).", MAX_ATTEMPTS, now())
            } catch (e: IOException) {
                // No connection, Drive busy (CloudUnavailableException), a session gone mid-way:
                // nothing is wrong with the song. Try again later, from the saved offset.
                Log.w(TAG, "uploads paused: ${e.javaClass.simpleName}")
                return if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
            } catch (e: Exception) {
                // A file that can't be read (permission gone on an SD card...): one song's problem.
                Log.w(TAG, "upload of one song failed: ${e.javaClass.simpleName}")
                dao.failUploadAttempt(row.trackId, "Couldn't read this song's file.", MAX_ATTEMPTS, now())
            }
        }
        if (done > 0) Log.i(TAG, "saved $done songs to Google Drive")
        return Result.success()
    }

    private fun now() = System.currentTimeMillis()

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

    /** Stops uploading (disconnect). Sessions stay on their rows; no file is touched in Drive. */
    fun cancel()

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

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE)
    }

    override val running: Flow<Boolean>
        get() = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    override val wifiOnly: Flow<Boolean> get() = syncPreferences.preferences.map { it.wifiOnly }

    companion object {
        const val UNIQUE = "cloud_upload"
    }
}
