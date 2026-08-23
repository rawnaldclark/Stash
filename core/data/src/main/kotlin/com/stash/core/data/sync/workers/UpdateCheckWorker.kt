package com.stash.core.data.sync.workers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.work.workDataOf
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
        /**
         * Unique name for the manual/cold-start check. Public so the Settings
         * screen can observe THIS work and report its outcome — before, every
         * terminal path returned a bare `Result.success()` and a user on the
         * latest release saw "Checking for updates…" and then nothing (#377).
         */
        const val UNIQUE_ONE_SHOT_NAME = "stash_update_check_oneshot"

        /** Output key naming what the check concluded. */
        const val KEY_OUTCOME = "outcome"

        /** Output key carrying the remote tag, when one was read. */
        const val KEY_LATEST_TAG = "latest_tag"

        const val OUTCOME_UP_TO_DATE = "up_to_date"
        const val OUTCOME_UPDATE_AVAILABLE = "update_available"
        const val OUTCOME_FAILED = "failed"

        /**
         * Input flag marking a user-initiated check. A manual check must reach a
         * TERMINAL state so the UI can speak: a network failure becomes
         * `Result.failure` instead of `Result.retry`, which would otherwise
         * leave the observer waiting on work that never completes.
         */
        const val KEY_MANUAL = "manual"

        private const val PREFS_NAME = "update_check_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val RELEASES_URL =
            "https://api.github.com/repos/rawnaldclark/Stash/releases/latest"
        private const val DOWNLOAD_URL =
            "https://github.com/rawnaldclark/Stash/releases/latest"

        /** Lenient JSON parser that ignores unknown keys from the GitHub API. */
        private val json = Json { ignoreUnknownKeys = true }

        /** The outcome a completed check reports when the remote [isNewer]. */
        internal fun outcomeFor(isNewer: Boolean): String =
            if (isNewer) OUTCOME_UPDATE_AVAILABLE else OUTCOME_UP_TO_DATE

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
        fun enqueueOneTimeCheck(context: Context, manual: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                // A manual check runs UNCONSTRAINED on purpose. With
                // NetworkType.CONNECTED an offline tap leaves the work ENQUEUED
                // forever — the worker never starts, never reaches the manual
                // failure path, and the button that's waiting on a finished
                // WorkInfo stays stuck on "Checking…". Someone who asked has a
                // right to a fast "couldn't check", so let the request run and
                // let the HTTP call fail. Background checks keep the constraint:
                // nobody is waiting, and deferring until there's a network is
                // exactly right for them.
                .apply {
                    if (!manual) {
                        setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                    }
                }
                .setInputData(workDataOf(KEY_MANUAL to manual))
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
                // Case-insensitive: an uppercase "V" used to survive the strip,
                // so "V1.0.0" parsed as [0,0,0] and every remote tag looked
                // newer than it.
                v.removePrefix("v").removePrefix("V")
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
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        return try {
            // An unreadable package manager won't fix itself on a retry, so the
            // background path still gives up quietly; a manual check says so.
            val installedVersion = getInstalledVersion() ?: return if (manual) {
                Result.failure(workDataOf(KEY_OUTCOME to OUTCOME_FAILED))
            } else {
                Result.success()
            }
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("User-Agent", "Stash-Android/$installedVersion")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return giveUpOrRetry(manual)
            }

            val body = response.body?.string() ?: return giveUpOrRetry(manual)
            val root = json.parseToJsonElement(body).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.content
                ?: return giveUpOrRetry(manual)
            val releaseName = root["name"]?.jsonPrimitive?.content ?: tagName

            val outcome = outcomeFor(isNewerVersion(tagName, installedVersion))
            val outcomeData = workDataOf(KEY_OUTCOME to outcome, KEY_LATEST_TAG to tagName)

            if (outcome == OUTCOME_UP_TO_DATE) {
                Log.d(TAG, "Already on latest ($installedVersion), remote is $tagName")
                return Result.success(outcomeData)
            }

            // Check if we already notified for this exact version.
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
            if (lastNotified == tagName) {
                // Suppresses a DUPLICATE NOTIFICATION, never the answer: a user
                // who dismissed the notice once and then asks again still gets
                // told an update exists (#377).
                Log.d(TAG, "Already notified for $tagName, skipping notification")
                return Result.success(outcomeData)
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

            Result.success(outcomeData)
        } catch (e: Exception) {
            // Don't turn a cancelled worker into a retry — rethrow the CE.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Update check failed", e)
            giveUpOrRetry(manual)
        }
    }

    /**
     * A failed check's terminal state.
     *
     * A background check retries — it has no one waiting. A MANUAL check must
     * fail loudly instead: `Result.retry()` leaves the work RUNNING/ENQUEUED
     * forever from an observer's point of view, which is the silence #377
     * reported. [Result.failure] is terminal, so the UI can say so.
     */
    private fun giveUpOrRetry(manual: Boolean): Result =
        if (manual) {
            Result.failure(workDataOf(KEY_OUTCOME to OUTCOME_FAILED))
        } else {
            Result.retry()
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

        val packageManager = applicationContext.packageManager

        // Discover installed apps that are actually browsers by querying for
        // handlers of a GENERIC http:// URL — this is the same signal Android
        // itself uses to populate "Open with…" browser pickers. Avoids
        // hardcoding a package allowlist (which excludes Vivaldi, Edge, Opera,
        // Samsung Internet, Kiwi, DuckDuckGo, etc.) while still preferring a
        // genuine browser over some unrelated app that happens to register an
        // intent-filter for github.com links (e.g. a GitHub client).
        val genericBrowserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val browserPackages = packageManager.queryIntentActivities(
            genericBrowserIntent,
            PackageManager.MATCH_ALL,
        ).mapNotNull { it.activityInfo?.packageName }.toSet()

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
        val resolved = packageManager.queryIntentActivities(
            browserIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        // Prefer a resolver that's a genuine browser; otherwise fall back to
        // whatever the system resolved. Then make the launch explicit by
        // binding to the chosen activity component.
        val preferredActivity = resolved
            .firstOrNull { info -> info.activityInfo?.packageName in browserPackages }
            ?: resolved.firstOrNull()

        if (preferredActivity?.activityInfo == null) {
            Log.w(TAG, "No browser activity resolved for update URL; skipping notification")
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setClassName(
                preferredActivity.activityInfo.packageName,
                preferredActivity.activityInfo.name,
            )
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
