package com.stash.core.common.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The tap target for a Stash notification that has no screen of its own: opens Stash the way the
 * launcher icon does. A tap on any notification should always go somewhere, and "somewhere" is
 * Stash itself unless a deep link (Downloads, the player) is a better fit.
 *
 * @param requestCode Distinguishes this PendingIntent from the others the process creates. All
 *   of them share one launch Intent, which the system treats as equal, so each notification
 *   passes its own code.
 * @return Null when the package has no launcher activity (the caller then posts without a tap).
 */
fun openStashPendingIntent(context: Context, requestCode: Int): PendingIntent? {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ?: return null
    return PendingIntent.getActivity(
        context,
        requestCode,
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Request codes for [openStashPendingIntent], one per notification. They must differ from each
 * other and from the Downloads shortcut's (9101, same launch Intent plus an extra, which
 * PendingIntent matching ignores), or one tap target would overwrite another's.
 */
object OpenStashRequest {
    const val SLEEP_TIMER = 9111
    const val SYNC_COMPLETE = 9112
    const val FLAC_UPGRADE_SUMMARY = 9113
    const val CLOUD_UPLOAD = 9114
    const val LYRICS_PROGRESS = 9115
    const val LYRICS_DONE = 9116
}
