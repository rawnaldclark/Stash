package com.stash.feature.record.imports

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stash.feature.record.R

/** What the import tells the person outside the app: a notification when it came to nothing (the card says so too). */
internal interface ImportNotices {
    /** The import came to nothing: "Your import didn't finish", tapping it opens Stash (the Home card offers Try again). */
    fun unfinished()

    /** Tried again, dismissed, or a new import begun: the notification goes. */
    fun clear()

    /** No notifications (tests). */
    object None : ImportNotices {
        override fun unfinished() = Unit
        override fun clear() = Unit
    }
}

/**
 * [ImportNotices] as Android notifications, on the import's own channel (the one its progress uses: quiet, no sound). Best
 * effort: with notifications turned off for Stash, the card alone says it.
 */
internal class ImportNotifications(private val context: Context) : ImportNotices {
    override fun unfinished() {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, UNFINISHED_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.record_unfinished_title))
            .setContentText(context.getString(R.string.record_unfinished_notice))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.record_unfinished_notice)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        try {
            nm.notify(UNFINISHED_ID, n)
        } catch (e: SecurityException) {
            // Android 13+ without the notification permission: the card says it
            Log.w(TAG, "the import's notice wasn't shown: no permission")
        }
    }

    override fun clear() = NotificationManagerCompat.from(context).cancel(UNFINISHED_ID)

    companion object {
        private const val TAG = "YourRecord"
        const val CHANNEL_ID = "record_import"

        /** The progress notification's id is the worker's (9481); this is the one left behind. */
        private const val UNFINISHED_ID = 9482

        /** The import's channel (progress and this notice), made once. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.record_import_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
