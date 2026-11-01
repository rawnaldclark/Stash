package com.stash.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.stash.core.media.widget.WidgetKeyRelay
import com.stash.core.media.widget.WidgetMediaIntents
import com.stash.core.media.widget.WidgetTransport
import dagger.hilt.android.EntryPointAccessors

/**
 * The wide "Now playing" widget in the picker (4×1 by default, `xml/now_playing_widget_info`).
 * The same [NowPlayingWidget] as the square one; the picker shows each with its own preview.
 */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** The square "Now playing" widget in the picker (2×2 by default, `xml/now_playing_widget_square_info`). */
class NowPlayingSquareWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/**
 * The widget's Pause and Next while a song plays ([WidgetKeyRelay]). While the playback service is
 * alive the key goes on to it, as the notification's does. With the service gone (a crash skipped
 * onDestroy, so the launcher still shows Pause, and a plain service start from an idle app would be
 * refused) the widget is redrawn as it really is: paused, with a Play that starts the service again.
 */
class NowPlayingWidgetKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(app, NowPlayingWidgetEntryPoint::class.java)
        val alive = entryPoint.playbackSessionBus().sessionAlive.value
        when (val route = WidgetKeyRelay.route(intent, serviceAlive = alive)) {
            WidgetKeyRelay.Route.Ignore -> Unit
            is WidgetKeyRelay.Route.Forward -> if (!forward(app, route.transport)) redraw(entryPoint)
            WidgetKeyRelay.Route.Redraw -> redraw(entryPoint)
        }
    }

    /** Sends the key to the running service; false when Android refused the start (it wasn't in the foreground). */
    private fun forward(context: Context, transport: WidgetTransport): Boolean = try {
        context.startService(WidgetMediaIntents.mediaButton(context, transport)) != null
    } catch (e: IllegalStateException) {
        Log.w(TAG, "widget key not forwarded: the service can't be started from the background")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "widget key not forwarded: ${e.javaClass.simpleName}")
        false
    }

    /** Marks the song paused and redraws every widget, holding the broadcast open until that's done. */
    private fun redraw(entryPoint: NowPlayingWidgetEntryPoint) {
        val pending = goAsync()
        entryPoint.widgetSink().redrawAfterLostService(onDone = pending::finish)
    }

    private companion object {
        const val TAG = "StashWidget"
    }
}
