package com.stash.core.media.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.stash.core.media.service.StashPlaybackService

/**
 * The intents behind the widget's taps. See [WidgetMediaIntentsTest].
 *
 * Media buttons are built exactly like the media notification's (Media3 DefaultActionFactory
 * getMediaButtonIntent): ACTION_MEDIA_BUTTON, explicit to the playback service, the key as an
 * ACTION_DOWN [KeyEvent] in EXTRA_KEY_EVENT. Glance wraps each one in its own PendingIntent (and
 * gives it a unique data URI, which MediaSessionService.onStartCommand doesn't know and so answers
 * with onGetSession, the same path a Bluetooth button takes). A stopped service is started by it and
 * resumes the saved queue through onPlaybackResumption.
 */
object WidgetMediaIntents {

    /**
     * Boolean extra on a widget Next that has to start playback: the service skips one song before
     * the Play key is handled, or, when it has nothing loaded, resumes the saved queue one song on.
     */
    const val EXTRA_SKIP_FIRST = "com.stash.extra.WIDGET_SKIP_FIRST"

    /** The media-button intent for [transport]. */
    fun mediaButton(context: Context, transport: WidgetTransport): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, StashPlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, transport.keyCode))
            .apply { if (transport.skipFirst) putExtra(EXTRA_SKIP_FIRST, true) }

    /** True for a widget Next that asks the service to skip one song before playing. */
    fun isSkipFirst(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MEDIA_BUTTON && intent.getBooleanExtra(EXTRA_SKIP_FIRST, false)

    /**
     * Opens the app on Now Playing: the media notification's own intent (see
     * [StashPlaybackService.openPlayerIntent]); null if the app has no launcher activity.
     */
    fun openPlayer(context: Context): Intent? = StashPlaybackService.openPlayerIntent(context)

    /** Opens the app where it was (the "Nothing playing" widget). */
    fun openApp(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)
}
