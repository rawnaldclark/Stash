package com.stash.core.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.stash.core.data.R
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notification channels and notifications for sync operations.
 *
 * Provides an ongoing progress notification for the foreground sync service
 * and a summary notification posted when a sync run completes.
 */
@Singleton
class SyncNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        /** Channel ID for the ongoing sync progress notification. */
        const val CHANNEL_SYNC_PROGRESS = "sync_progress"

        /** Channel ID for the post-sync summary notification. */
        const val CHANNEL_SYNC_SUMMARY = "sync_summary"

        /** Notification ID for the foreground service progress notification. */
        const val NOTIFICATION_ID_PROGRESS = 9001

        /** Notification ID for the completion summary notification. */
        const val NOTIFICATION_ID_SUMMARY = 9002

        /** Channel ID for app update availability notifications. */
        const val CHANNEL_UPDATE = "update_channel"

        /** Notification ID for the app update notification. */
        const val NOTIFICATION_ID_UPDATE = 9003

        /**
         * Channel ID for lossless-source events that need user
         * attention — currently only the "captcha expired" prompt
         * for squid.wtf, but a likely home for future per-source
         * configuration nudges (rate-limit hit, source down, etc).
         */
        const val CHANNEL_LOSSLESS = "lossless_channel"

        /** Notification ID for the captcha-expired nudge. */
        const val NOTIFICATION_ID_LOSSLESS_CAPTCHA = 9004

        /** Foreground-progress ID for the batch FLAC-upgrade worker. */
        const val NOTIFICATION_ID_FLAC_UPGRADE = 9005

        /** Summary ID for a finished FLAC batch — separate from the sync
         *  summary so one never clobbers the other. */
        const val NOTIFICATION_ID_FLAC_SUMMARY = 9006

        /** Foreground-progress ID for downloads outside a sync (a playlist's
         *  Download button, a followed mix). Not [NOTIFICATION_ID_PROGRESS]:
         *  the two run at once, and a shared ID made them overwrite each other. */
        const val NOTIFICATION_ID_DOWNLOADS = 9007

        /** Foreground ID for the "Building your mix" discovery run, which Android
         *  11 and older show while it runs expedited. Its own ID for the same
         *  reason as [NOTIFICATION_ID_DOWNLOADS]: it runs beside a sync. */
        const val NOTIFICATION_ID_DISCOVERY = 9008

        /** Foreground ID for the resume run (a sync's songs downloading outside a sync). Its own ID: it
         *  runs beside the drain ([NOTIFICATION_ID_DOWNLOADS]) and a sync. */
        const val NOTIFICATION_ID_RESUME = 9009

        /** "Downloads paused": the phone ran out of space. */
        const val NOTIFICATION_ID_DOWNLOADS_PAUSED = 9010

        /** "Downloads slowed down": YouTube asked us to wait. */
        const val NOTIFICATION_ID_RATE_LIMITED = 9011

        /** Launch-intent extra: open the Downloads screen (a download notification was tapped). */
        const val EXTRA_OPEN_DOWNLOADS = "com.stash.open_downloads"

        private const val REQUEST_OPEN_DOWNLOADS = 9101
        private const val REQUEST_PAUSE = 9102
        private const val REQUEST_RESUME = 9103

        /** Maximum value for the determinate progress bar. */
        private const val PROGRESS_MAX = 100
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Create the notification channels required for sync operations.
     *
     * Safe to call multiple times; the system ignores duplicate creation.
     * Should be called once from [android.app.Application.onCreate].
     */
    fun createChannels() {
        val progressChannel = NotificationChannel(
            CHANNEL_SYNC_PROGRESS,
            "Sync Progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing notification shown while syncing playlists"
            setShowBadge(false)
        }

        val summaryChannel = NotificationChannel(
            CHANNEL_SYNC_SUMMARY,
            "Sync Summary",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Summary notification after a sync completes"
        }

        val updateChannel = NotificationChannel(
            CHANNEL_UPDATE,
            "App Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notification when a new version of Stash is available"
        }

        val losslessChannel = NotificationChannel(
            CHANNEL_LOSSLESS,
            "Lossless Source",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts that affect lossless downloads — currently the squid.wtf captcha-expired prompt"
        }

        notificationManager.createNotificationChannels(
            listOf(progressChannel, summaryChannel, updateChannel, losslessChannel)
        )
    }

    /**
     * Post a "Lossless captcha expired — tap to verify" notification.
     * [contentIntent] is the deep-link to the captcha verify screen.
     * Caller (typically [CaptchaExpiredNotifier]) is responsible for
     * debouncing — this method always notifies, never throttles.
     */
    fun showLosslessCaptchaExpired(contentIntent: PendingIntent) {
        val notification = NotificationCompat.Builder(context, CHANNEL_LOSSLESS)
            .setContentTitle("Lossless captcha expired")
            .setContentText("squid.wtf needs a fresh captcha solve. Tap to verify.")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        notificationManager.notify(NOTIFICATION_ID_LOSSLESS_CAPTCHA, notification)
    }

    /** Cancel the captcha-expired notification, e.g. when the user just refreshed the cookie. */
    fun cancelLosslessCaptchaExpired() {
        notificationManager.cancel(NOTIFICATION_ID_LOSSLESS_CAPTCHA)
    }

    /**
     * Build a progress notification suitable for a foreground service.
     *
     * @param title             Notification title (e.g. "Syncing playlists").
     * @param text              Notification body text (e.g. "Downloading track 3 of 20").
     * @param progress          Current progress as a float in [0.0, 1.0]. Pass
     *                          a negative value for an indeterminate spinner.
     * @param cancelIntent      Optional [PendingIntent] to wire a "Cancel"
     *                          action to — typically obtained from
     *                          [androidx.work.WorkManager.createCancelPendingIntent].
     * @param pauseIntent       Optional "Pause" action ([pauseDownloadsIntent]): the download
     *                          notifications pause the queue rather than cancel it.
     * @param contentIntent     What a tap on the notification opens ([openDownloadsIntent]).
     * @return A built [Notification] instance.
     */
    fun buildProgressNotification(
        title: String,
        text: String,
        progress: Float,
        cancelIntent: PendingIntent? = null,
        pauseIntent: PendingIntent? = null,
        contentIntent: PendingIntent? = null,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (progress < 0f) {
            builder.setProgress(0, 0, true) // indeterminate spinner
        } else {
            builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
        }

        if (cancelIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelIntent,
            )
        }
        if (pauseIntent != null) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notif_action_pause),
                pauseIntent,
            )
        }
        if (contentIntent != null) builder.setContentIntent(contentIntent)

        return builder.build()
    }

    /**
     * Update the existing progress notification in place.
     *
     * @param title    Updated title.
     * @param text     Updated body text.
     * @param progress Current progress as a float in [0.0, 1.0].
     * @param cancelIntent The worker's cancel intent. Pass it whenever the
     *                 notification being replaced had one: this posts under the
     *                 same id, so without it the first update strips the
     *                 foreground notification's "Cancel" action.
     */
    fun updateProgress(
        title: String,
        text: String,
        progress: Float,
        cancelIntent: PendingIntent? = null,
        pauseIntent: PendingIntent? = null,
        contentIntent: PendingIntent? = null,
        notificationId: Int = NOTIFICATION_ID_PROGRESS,
    ) {
        val notification = buildProgressNotification(title, text, progress, cancelIntent, pauseIntent, contentIntent)
        notificationManager.notify(notificationId, notification)
    }

    /** Opens the app on the Downloads screen (MainActivity reads [EXTRA_OPEN_DOWNLOADS]). Null without a launcher activity. */
    fun openDownloadsIntent(): PendingIntent? {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_OPEN_DOWNLOADS, true)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_DOWNLOADS,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The download notifications' Pause: [DownloadControlReceiver] pauses the queue. */
    fun pauseDownloadsIntent(): PendingIntent = controlIntent(DownloadControlReceiver.ACTION_PAUSE, REQUEST_PAUSE)

    /** "Downloads paused"'s Resume: [DownloadControlReceiver] resumes the queue as the user. */
    fun resumeDownloadsIntent(): PendingIntent = controlIntent(DownloadControlReceiver.ACTION_RESUME, REQUEST_RESUME)

    private fun controlIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, DownloadControlReceiver::class.java).setAction(action).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * "Downloads paused": a download ran out of space. Nothing retries on its own until Resume
     * (its action) or the next cold start; a tap opens Downloads.
     */
    fun showStorageFull() {
        val text = context.getString(R.string.notif_storage_full_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_SUMMARY)
            .setContentTitle(context.getString(R.string.notif_storage_full_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { openDownloadsIntent()?.let(::setContentIntent) }
            .addAction(android.R.drawable.ic_media_play, context.getString(R.string.notif_action_resume), resumeDownloadsIntent())
            .build()
        notificationManager.notify(NOTIFICATION_ID_DOWNLOADS_PAUSED, notification)
    }

    /** The out-of-space notification goes once downloads resume. */
    fun cancelDownloadsPaused() {
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOADS_PAUSED)
    }

    /**
     * "Downloads slowed down", silently: YouTube asked us to wait, and downloads try again around
     * [retryAtMs] (shown in the phone's own time format).
     */
    fun showRateLimited(retryAtMs: Long) {
        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(retryAtMs))
        val text = context.getString(R.string.notif_rate_limited_text, time)
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_SUMMARY)
            .setContentTitle(context.getString(R.string.notif_rate_limited_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setSilent(true)
            .setAutoCancel(true)
            .apply { openDownloadsIntent()?.let(::setContentIntent) }
            .build()
        notificationManager.notify(NOTIFICATION_ID_RATE_LIMITED, notification)
    }

    /** The resume run ended: its progress notification goes (one posted outside the foreground would stay). */
    fun cancelResumeProgress() {
        notificationManager.cancel(NOTIFICATION_ID_RESUME)
    }

    /** Pause: the resume run's and the drain's progress notifications go (WorkManager drops a foreground one itself). */
    fun cancelDownloadProgress() {
        notificationManager.cancel(NOTIFICATION_ID_RESUME)
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOADS)
    }

    /**
     * Show a summary notification after sync completion.
     *
     * @param newTracks        Number of new tracks downloaded.
     * @param playlistsChecked Number of playlists that were inspected.
     * @param failed           Number of tracks that failed to download.
     * @param waiting          Songs the sync left queued, which download later on their own.
     *                         With any left (or failed) the summary says so and opens Downloads,
     *                         instead of "Everything up to date".
     */
    fun showSummary(newTracks: Int, playlistsChecked: Int, failed: Int, waiting: Int = 0) {
        if (waiting > 0 || failed > 0) {
            val text = buildList {
                add(context.resources.getQuantityString(R.plurals.notif_summary_downloaded, newTracks, newTracks))
                if (waiting > 0) add(context.getString(R.string.notif_summary_try_later, waiting))
                if (failed > 0) add(context.getString(R.string.notif_summary_failed, failed))
            }.joinToString(" · ")
            val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_SUMMARY)
                .setContentTitle("Sync Complete")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .apply { openDownloadsIntent()?.let(::setContentIntent) }
                .build()
            notificationManager.notify(NOTIFICATION_ID_SUMMARY, notification)
            return
        }
        val text = buildString {
            append("Checked $playlistsChecked playlist(s). ")
            if (newTracks > 0) {
                append("$newTracks new track(s) downloaded. ")
            } else {
                append("Everything up to date. ")
            }
            if (failed > 0) {
                append("$failed failed.")
            }
        }.trim()

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_SUMMARY)
            .setContentTitle("Sync Complete")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SUMMARY, notification)
    }

    /**
     * Cancel the ongoing progress notification.
     *
     * Should be called when the sync foreground service stops.
     */
    fun cancelProgress() {
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
    }

    /** Cancel the ongoing FLAC-upgrade progress notification. Distinct from
     *  [cancelProgress] (sync's own notification, ID 9001) — the two run
     *  under separate IDs and must be cancelled independently. */
    fun cancelFlacUpgrade() {
        notificationManager.cancel(NOTIFICATION_ID_FLAC_UPGRADE)
    }

    /** Summary for a finished batch FLAC upgrade (spec 2026-07-22 §3). */
    fun showFlacUpgradeSummary(upgraded: Int, noMatch: Int, failed: Int, skipped: Int) {
        val text = buildList {
            add("$upgraded upgraded")
            if (noMatch > 0) add("$noMatch no FLAC found")
            if (failed > 0) add("$failed failed")
            // Songs whose audio the user picked in Failed Matches (#531).
            if (skipped > 0) add("$skipped kept the version you picked")
        }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_SUMMARY)
            .setContentTitle("FLAC upgrade complete")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_FLAC_SUMMARY, notification)
    }
}
