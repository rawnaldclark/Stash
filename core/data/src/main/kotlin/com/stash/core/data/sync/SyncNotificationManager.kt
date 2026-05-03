package com.stash.core.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
     * @return A built [Notification] instance.
     */
    fun buildProgressNotification(
        title: String,
        text: String,
        progress: Float,
        cancelIntent: PendingIntent? = null,
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

        return builder.build()
    }

    /**
     * Update the existing progress notification in place.
     *
     * @param title    Updated title.
     * @param text     Updated body text.
     * @param progress Current progress as a float in [0.0, 1.0].
     */
    fun updateProgress(title: String, text: String, progress: Float) {
        val notification = buildProgressNotification(title, text, progress)
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    /**
     * Show a summary notification after sync completion.
     *
     * @param newTracks        Number of new tracks downloaded.
     * @param playlistsChecked Number of playlists that were inspected.
     * @param failed           Number of tracks that failed to download.
     */
    fun showSummary(newTracks: Int, playlistsChecked: Int, failed: Int) {
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
}
