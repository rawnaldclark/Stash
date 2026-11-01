package com.stash.core.media.widget

import android.view.KeyEvent

/**
 * One widget button's media-button event: the key it sends, whether its PendingIntent must start
 * the service in the foreground, and whether the service skips one song before playing.
 */
data class WidgetTransport(
    /** A KeyEvent.KEYCODE_MEDIA_* code, sent as ACTION_DOWN like the notification's buttons. */
    val keyCode: Int,
    /**
     * True: PendingIntent.getForegroundService. Required whenever the tap may have to start a
     * stopped service (Android 8+ refuses a plain background service start), and allowed only when
     * the tap ends in playback, which posts the foreground notification in time. False:
     * PendingIntent.getService, for a service already running in the foreground (it plays).
     */
    val foreground: Boolean,
    /** Skip to the next song first (see [WidgetMediaIntents.EXTRA_SKIP_FIRST]). */
    val skipFirst: Boolean = false,
)

/** The widget's two buttons for one state. */
data class WidgetButtons(
    /** Show the Pause glyph (else Play). */
    val showsPause: Boolean,
    val playPause: WidgetTransport,
    val next: WidgetTransport,
)

/**
 * Which media-button events the widget's buttons send. Pure; see [WidgetControlsTest].
 *
 * Mirrors the media notification (Media3's DefaultActionFactory): the event goes straight to
 * [StashPlaybackService][com.stash.core.media.service.StashPlaybackService], not through
 * androidx.media3.session.MediaButtonReceiver, which drops every key but Play on Android 8+.
 * Two deliberate differences:
 *  - Explicit PLAY and PAUSE keys instead of PLAY_PAUSE, so a widget that is out of date never does
 *    the opposite of the glyph the user tapped.
 *  - Next while nothing plays is "skip, then play", started in the foreground. A paused or stopped
 *    service can't be reached with a plain service start from the background (and after the
 *    5-minute idle stop there is no service at all), and a foreground start must end in playback.
 *    So from the home screen, Next always ends with the next song playing.
 */
object WidgetControls {

    /**
     * The buttons for [state], or null when there is no song (the widget then only opens the app).
     *
     * @param serviceRunning whether the playback service is alive in this process right now
     *   ([com.stash.core.media.PlaybackSessionBus.sessionAlive]). A stored "playing" is only
     *   believed while it is: after process death the widget shows Play.
     */
    fun of(state: NowPlayingWidgetState?, serviceRunning: Boolean): WidgetButtons? {
        if (state == null) return null
        return if (state.isPlaying && serviceRunning) {
            WidgetButtons(
                showsPause = true,
                playPause = WidgetTransport(KeyEvent.KEYCODE_MEDIA_PAUSE, foreground = false),
                next = WidgetTransport(KeyEvent.KEYCODE_MEDIA_NEXT, foreground = false),
            )
        } else {
            WidgetButtons(
                showsPause = false,
                playPause = WidgetTransport(KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true),
                next = WidgetTransport(KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true, skipFirst = true),
            )
        }
    }
}
