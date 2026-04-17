package com.stash.core.data.sync.workers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.sync.SyncNotificationManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that checks the public GitHub Releases API
 * for a newer version of Stash.
 *
 * This worker is intentionally NOT a HiltWorker — it has no injected
 * dependencies. It creates a lightweight [OkHttpClient] per invocation,
 * parses the JSON response with kotlinx.serialization, and posts a
 * notification when a newer release is found.
 *
 * To avoid duplicate notifications for the same release, the last-notified
 * version tag is persisted in SharedPreferences.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val UNIQUE_WORK_NAME = "stash_update_check"
        private const val UNIQUE_ONE_SHOT_NAME = "stash_update_check_oneshot"
        private const val PREFS_NAME = "update_check_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val RELEASES_URL =
            "https://api.github.com/repos/rawnaldclark/Stash/releases/latest"
        private const val DOWNLOAD_URL =
            "https://github.com/rawnaldclark/Stash/releases/latest"

        /** Lenient JSON parser that ignores unknown keys from the GitHub API. */
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Enqueues a periodic update-check job that runs every 24 hours.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling is idempotent.
         */
        fun schedulePeriodicCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Fires a single update check immediately (subject to a network
         * constraint). Used on every app cold-start so a release pushed
         * between two periodic-worker windows is surfaced within seconds of
         * the next launch, and by the Settings "Check for updates" button.
         *
         * [ExistingWorkPolicy.REPLACE] ensures a stale queued check doesn't
         * starve a fresh request; multiple rapid calls collapse to one.
         */
        fun enqueueOneTimeCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /**
         * Compare two semantic version strings numerically.
         *
         * Strips a leading "v" prefix and any pre-release suffix (e.g. "-beta",
         * "-rc.1") before comparing. Each dotted segment is compared as an
         * integer, so "0.10.0" is correctly recognised as greater than "0.2.0".
         *
         * @return `true` if [remote] is strictly newer than [local].
         */
        internal fun isNewerVersion(remote: String, local: String): Boolean {
            val clean = { v: String ->
                v.removePrefix("v")
                    .replace(Regex("-.*"), "") // strip -beta, -rc.1, etc.
                    .split(".")
                    .map { it.toIntOrNull() ?: 0 }
            }

            val remoteParts = clean(remote)
            val localParts = clean(local)
            val maxLen = maxOf(remoteParts.size, localParts.size)

            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val installedVersion = getInstalledVersion() ?: return Result.success()
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("User-Agent", "Stash-Android/$installedVersion")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return Result.retry()
            }

            val body = response.body?.string() ?: return Result.retry()
            val root = json.parseToJsonElement(body).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.content ?: return Result.success()
            val releaseName = root["name"]?.jsonPrimitive?.content ?: tagName

            if (!isNewerVersion(tagName, installedVersion)) {
                Log.d(TAG, "Already on latest ($installedVersion), remote is $tagName")
                return Result.success()
            }

            // Check if we already notified for this exact version.
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
            if (lastNotified == tagName) {
                Log.d(TAG, "Already notified for $tagName, skipping")
                return Result.success()
            }

            val notified = showUpdateNotification(tagName, releaseName)

            // Only persist if the notification actually surfaced. If the OS
            // suppressed it (permission denied, channel muted, app in
            // background restriction), the next worker run will try again —
            // otherwise a permission granted later would never get a retry.
            if (notified) {
                prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, tagName).apply()
            } else {
                Log.w(TAG, "notify() suppressed for $tagName; will retry on next run")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            Result.retry()
        }
    }

    /**
     * Reads the installed app version from the package manager.
     *
     * @return The versionName string (e.g. "0.2.0"), or null if unavailable.
     */
    private fun getInstalledVersion(): String? {
        return try {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
                .versionName
        } catch (e: Exception) {
            Log.e(TAG, "Could not read installed version", e)
            null
        }
    }

    /**
     * Posts a notification informing the user that a newer release is available.
     * Tapping the notification opens the GitHub releases page in the browser.
     *
     * @return `true` if the notification was accepted by the OS, `false` if
     *   it was suppressed (notifications disabled globally, channel muted,
     *   or POST_NOTIFICATIONS permission denied on Android 13+).
     */
    private fun showUpdateNotification(tag: String, releaseName: String): Boolean {
        val managerCompat = NotificationManagerCompat.from(applicationContext)
        if (!managerCompat.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled by user; skipping")
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            SyncNotificationManager.CHANNEL_UPDATE,
        )
            .setContentTitle("Stash Update Available")
            .setContentText("Version $releaseName is ready. Tap to download.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            managerCompat.notify(
                SyncNotificationManager.NOTIFICATION_ID_UPDATE,
                notification,
            )
            true
        } catch (e: SecurityException) {
            // Android 13+ with POST_NOTIFICATIONS revoked throws here.
            Log.w(TAG, "notify() threw SecurityException — permission not granted", e)
            false
        }
    }
}
