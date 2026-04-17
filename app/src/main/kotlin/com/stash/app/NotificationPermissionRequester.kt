package com.stash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Requests the Android 13+ `POST_NOTIFICATIONS` runtime permission exactly
 * once per install. If the user denies, we don't re-prompt — future launches
 * simply skip notifications cleanly (see [UpdateCheckWorker] and
 * [SyncNotificationManager], both of which check `areNotificationsEnabled()`
 * before posting).
 *
 * The one-prompt guard uses [SharedPreferences] keyed by
 * [PREF_KEY_PROMPTED] so the system dialog doesn't re-appear on every cold
 * start (which would be annoying on a fresh denial).
 *
 * On pre-Android-13 this is a manifest-only permission and the composable
 * is a no-op.
 */
@Composable
fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Whether or not the user granted, mark as prompted so we don't ask
        // again on every launch. User can re-enable via system Settings.
        context.markPromptedForNotifications()
        if (granted) {
            // Kick off a one-shot update check now that we can actually post
            // the notification. If a release was published while the user
            // was ignoring previous silent failures, they'll see it within
            // seconds of granting permission.
            com.stash.core.data.sync.workers.UpdateCheckWorker.enqueueOneTimeCheck(context)
        }
    }

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return@LaunchedEffect
        if (context.wasPromptedForNotifications()) return@LaunchedEffect
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val PREFS = "notification_permission_prefs"
private const val PREF_KEY_PROMPTED = "post_notifications_prompted"

private fun Context.wasPromptedForNotifications(): Boolean =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_KEY_PROMPTED, false)

private fun Context.markPromptedForNotifications() {
    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_KEY_PROMPTED, true).apply()
}
