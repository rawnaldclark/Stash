package com.stash.core.media.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * The widget's Pause and Next while a song plays, relayed through the app's own receiver instead
 * of a plain service start. See [WidgetKeyRelayTest].
 *
 * Why: the launcher keeps the widget's last picture after the app dies without onDestroy (a crash,
 * a kill from the task manager), so it can still show Pause. A plain service start from an idle
 * app is refused (Android 8+), so a Pause or Next sent straight to the service would do nothing:
 * both buttons dead until the next song. The receiver can always run. It forwards the key to the
 * playback service while one is alive (playing, so in the foreground, so the start is allowed)
 * and otherwise redraws the widget as it really is: paused, with a Play that starts the service in
 * the foreground ([WidgetTransport.foreground]).
 */
object WidgetKeyRelay {

    /** The relayed tap's action; the receiver is not exported, so only the app's own widget sends it. */
    const val ACTION = "com.stash.action.WIDGET_MEDIA_KEY"

    /** Int extra: the KeyEvent.KEYCODE_MEDIA_* code to forward. */
    private const val EXTRA_KEY_CODE = "com.stash.extra.WIDGET_MEDIA_KEY"

    /** The keys a relayed tap may carry; anything else is ignored. */
    private val RELAYED_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT)

    /** What the receiver does with one relayed tap. */
    sealed interface Route {
        /** Send [transport] to the running playback service, as the notification would. */
        data class Forward(val transport: WidgetTransport) : Route

        /** The service is gone: mark the song paused and redraw the widget (its Play then works). */
        data object Redraw : Route

        /** Not a relayed tap, or a key the widget never relays. */
        data object Ignore : Route
    }

    /** The explicit broadcast to [receiver] for [transport] (a [WidgetTransport.foreground] = false key). */
    fun intent(context: Context, receiver: Class<out BroadcastReceiver>, transport: WidgetTransport): Intent =
        Intent(ACTION)
            .setComponent(ComponentName(context, receiver))
            .putExtra(EXTRA_KEY_CODE, transport.keyCode)

    /**
     * The route for [intent]. [serviceAlive]: the playback service is alive in this process
     * ([com.stash.core.media.PlaybackSessionBus.sessionAlive]); a receiver started in a fresh
     * process after a crash sees false.
     */
    fun route(intent: Intent?, serviceAlive: Boolean): Route {
        if (intent?.action != ACTION) return Route.Ignore
        val keyCode = intent.getIntExtra(EXTRA_KEY_CODE, KeyEvent.KEYCODE_UNKNOWN)
        if (keyCode !in RELAYED_KEYS) return Route.Ignore
        return if (serviceAlive) Route.Forward(WidgetTransport(keyCode, foreground = false)) else Route.Redraw
    }
}
