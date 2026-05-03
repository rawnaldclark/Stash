package com.stash.data.download.lossless.squid

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.data.download.lossless.LosslessSourcePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Posts a notification when squid.wtf returns "Captcha required" so
 * the user knows their lossless cookie expired and tracks are silently
 * falling back to yt-dlp. Tapping the notification deep-links to the
 * in-app captcha-verify WebView via [DEEP_LINK_TARGET].
 *
 * Two safeguards:
 *  - **Debounce:** during a single sync, hundreds of `download-music`
 *    calls might 403 in quick succession. We post at most one
 *    notification per [DEBOUNCE_MS], so the user gets one nudge per
 *    cookie-expiry event, not one per track.
 *  - **Auto-dismiss on cookie refresh:** when
 *    [LosslessSourcePreferences.captchaCookieValue] changes (the user
 *    re-pasted or solved via WebView), the notification is cleared
 *    automatically — no stale "captcha expired" sitting in the tray
 *    after they've just verified.
 */
@Singleton
class CaptchaExpiredNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncNotificationManager: SyncNotificationManager,
    prefs: LosslessSourcePreferences,
) {
    @Volatile private var lastNotifiedAtMs: Long = 0L

    /**
     * App-scoped — observes the cookie Flow forever. SupervisorJob so
     * a downstream collector failure doesn't propagate.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Cookie change ⇒ user has refreshed (paste or WebView solve).
        // Clear any stale notification so the tray doesn't lie.
        prefs.captchaCookieValue
            .distinctUntilChanged()
            .onEach {
                if (!it.isNullOrEmpty()) {
                    syncNotificationManager.cancelLosslessCaptchaExpired()
                    // Reset debounce so the next genuine expiry can
                    // re-notify quickly rather than waiting out the
                    // remainder of a previous window.
                    lastNotifiedAtMs = 0L
                }
            }
            .launchIn(scope)
    }

    /**
     * Idempotent — call freely. Inside the debounce window, no-op.
     */
    fun notifyExpired() {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAtMs < DEBOUNCE_MS) return
        lastNotifiedAtMs = now

        try {
            syncNotificationManager.showLosslessCaptchaExpired(buildContentIntent())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+. Not actionable
            // from here — the runtime asks once via
            // RequestNotificationPermissionOnce; if the user said no
            // there's nothing we can do silently.
            Log.w(TAG, "Notification permission denied; can't post captcha-expired notice", e)
        }
    }

    /**
     * Builds a launcher intent with the deep-link extra
     * [INTENT_EXTRA_NAV_TARGET] = [DEEP_LINK_TARGET]. MainActivity
     * reads this on launch (cold-start) and `onNewIntent` (warm-start)
     * and passes the target to the Compose nav graph.
     *
     * SINGLE_TOP keeps the existing back-stack if the app is already
     * in the foreground; CLEAR_TOP would wipe it. NEW_TASK is the
     * standard launcher behaviour for notification-borne intents.
     */
    private fun buildContentIntent(): PendingIntent {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(INTENT_EXTRA_NAV_TARGET, DEEP_LINK_TARGET)
            }
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val INTENT_EXTRA_NAV_TARGET = "stash.nav.target"
        const val DEEP_LINK_TARGET = "squid_wtf_captcha"

        private const val TAG = "CaptchaExpiredNotifier"
        private const val DEBOUNCE_MS = 5 * 60_000L  // 5 minutes
        private const val REQUEST_CODE = 9004
    }
}
