package com.stash.core.media.widget

/**
 * Where the playback service pushes the home screen widget's state. Implemented in the app module
 * (the widget is a Glance widget there; this module doesn't depend on Glance) and bound with Hilt.
 *
 * Both calls come from the service's main thread and must return at once: the implementation does
 * its disk, cover and RemoteViews work on its own background scope, which outlives the service
 * (the [serviceStopped] from onDestroy must still land).
 */
interface NowPlayingWidgetSink {

    /** The session settled on [state] (already debounced and de-duplicated). */
    fun publish(state: NowPlayingWidgetState)

    /** The service is stopping (the idle stop, onDestroy): whatever the widget shows is paused now. */
    fun serviceStopped()
}
