package com.stash.core.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

        notificationManager.createNotificationChannels(
            listOf(progressChannel, summaryChannel)
        )
    }

    /**
     * Build a progress notification suitable for a foreground service.
     *
     * @param title    Notification title (e.g. "Syncing playlists").
     * @param text     Notification body text (e.g. "Downloading track 3 of 20").
     * @param progress Current progress as a float in [0.0, 1.0].
     * @return A built [Notification] instance.
     */
    fun buildProgressNotification(
        title: String,
        text: String,
        progress: Float,
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_SYNC_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
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
