package com.stash.data.cloud.scan

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.data.cloud.CloudConfig
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudAuthRequiredException
import com.stash.data.cloud.provider.CloudItemNotFoundException
import com.stash.data.cloud.provider.gdrive.driveFolderRow
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the picked cloud folders (all, or one with [KEY_FOLDER_ID]) through [CloudScanner].
 *
 * A network failure ends the run with `retry()`; the next run continues from each folder's
 * saved cursor, so a 10-minute WorkManager window or a lost Wi-Fi costs nothing already done.
 * A sign-in that is gone marks the folder for "Reconnect" and does not retry.
 */
@HiltWorker
class CloudScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: CloudScanner,
    private val dao: CloudDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val only = inputData.getLong(KEY_FOLDER_ID, -1L).takeIf { it > 0 }
        val full = inputData.getBoolean(KEY_FULL, false)
        // Google Drive accounts connected before "Save to Google Drive" get their "Saved from
        // Stash" row, so songs saved from another phone are found too.
        if (only == null) {
            dao.getAccounts().filter { it.provider == com.stash.core.model.CloudProvider.GOOGLE_DRIVE.id }.forEach {
                try {
                    dao.driveFolderRow(it.id, com.stash.data.cloud.provider.gdrive.GoogleDriveProvider.UPLOADS_ITEM_ID, System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Disconnected meanwhile: nothing to scan for it.
                    Log.w(TAG, "saved-songs folder not made: ${e.javaClass.simpleName}")
                }
            }
        }
        val folders = if (only != null) listOfNotNull(dao.getFolder(only)) else dao.getAllFolders()
        var retry = false
        for (folder in folders) {
            try {
                val r = scanner.scan(folder.id, full) { n -> setProgressAsync(workDataOf(KEY_PROCESSED to n, KEY_FOLDER_ID to folder.id)) }
                if (r != null) Log.i(TAG, "folder ${folder.id}: +${r.added} ~${r.updated} -${r.removed} =${r.unchanged}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudAuthRequiredException) {
                mark(folder.id, CloudScanStatus.ERROR, "Sign-in expired. Reconnect the account.")
            } catch (e: CloudItemNotFoundException) {
                mark(folder.id, CloudScanStatus.ERROR, "This folder is gone or was moved.")
            } catch (e: CloudApiException) {
                Log.w(TAG, "folder ${folder.id} scan failed: HTTP ${e.status}")
                mark(folder.id, CloudScanStatus.ERROR, "The scan failed (error ${e.status}).")
            } catch (e: IOException) {
                Log.w(TAG, "folder ${folder.id} scan interrupted: ${e.javaClass.simpleName}")
                mark(folder.id, CloudScanStatus.WAITING, "Paused. It carries on when the connection is back.")
                retry = true
            }
        }
        return if (retry && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private suspend fun mark(folderId: Long, status: CloudScanStatus, message: String) {
        dao.getFolder(folderId)?.let { dao.updateFolder(it.copy(lastScanStatus = status.name, lastScanError = message)) }
    }

    companion object {
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_FULL = "full"
        const val KEY_PROCESSED = "processed"
        private const val TAG = "CloudScan"
        private const val MAX_ATTEMPTS = 8
    }
}

/**
 * Starts cloud scans. Network rule = the download setting (Wi-Fi only by default): a first scan
 * of a big library reads a few hundred KB per song.
 */
@Singleton
class CloudScanScheduler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val syncPreferences: SyncPreferencesManager,
    private val config: CloudConfig,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Scan [folderId] now (or every folder when null); [full] re-lists everything (Rescan now). */
    suspend fun scanNow(folderId: Long? = null, full: Boolean = false) {
        if (!config.anyEnabled) return
        val request = OneTimeWorkRequestBuilder<CloudScanWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(CloudScanWorker.KEY_FOLDER_ID to (folderId ?: -1L), CloudScanWorker.KEY_FULL to full))
            .addTag(TAG)
            .build()
        // APPEND: a "scan this new folder" asked during a full scan runs after it, not instead.
        workManager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Keeps an incremental scan (only changes) running twice a day while any folder is picked. */
    suspend fun ensurePeriodic() {
        if (!config.anyEnabled) return
        val request = PeriodicWorkRequestBuilder<CloudScanWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints())
            // Not now: the folder that made this call was just handed to scanNow. (The scanner's
            // per-folder lock also keeps two runs off one folder.)
            .setInitialDelay(12, TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(PERIODIC)
    }

    /**
     * Stops whatever scan is running (both the one-off and the periodic run), so a folder or
     * account can be removed without a scan writing into it. The scanner notices the
     * cancellation at its next suspension point and releases its folder lock.
     */
    fun cancelRunning() {
        workManager.cancelUniqueWork(UNIQUE)
        workManager.cancelUniqueWork(PERIODIC)
    }

    private suspend fun constraints(): Constraints {
        val wifiOnly = syncPreferences.preferences.first().wifiOnly
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    companion object {
        const val UNIQUE = "cloud_scan"
        const val PERIODIC = "cloud_scan_periodic"
        const val TAG = "cloud_scan"
    }
}
