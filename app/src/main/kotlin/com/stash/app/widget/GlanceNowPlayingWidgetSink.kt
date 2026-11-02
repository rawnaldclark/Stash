package com.stash.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.stash.core.media.widget.NowPlayingWidgetSink
import com.stash.core.media.widget.NowPlayingWidgetState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The playback service's pushes, turned into widget redraws: each one is stored
 * ([NowPlayingWidgetStore]) and every placed widget redrawn at once with the song's text; the cover
 * follows on its own worker and redraws again when it lands, so a slow fetch never holds the title
 * back. Pushes that arrive while one is being handled are applied together and redrawn once.
 * Nothing is polled; with no widget on the home screen a push costs one small file write.
 *
 * Runs on its own background scope, which lives as long as the process: the stop pushed from the
 * service's onDestroy must still land after the service's scope is gone.
 */
@Singleton
class GlanceNowPlayingWidgetSink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: NowPlayingWidgetStore,
) : NowPlayingWidgetSink {

    private sealed interface Op {
        data class Publish(val state: NowPlayingWidgetState) : Op
        data object Stopped : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)

    /** "Make the cover the song's": conflated, so a request during a fetch runs one more pass after it. */
    private val coverRequests = Channel<Unit>(Channel.CONFLATED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (op in ops) {
                var changed = apply(op)
                while (true) {
                    val next = ops.tryReceive().getOrNull() ?: break
                    changed = apply(next) || changed
                }
                // The text first, whatever the cover is doing.
                if (changed) redraw()
                coverRequests.trySend(Unit)
            }
        }
        scope.launch {
            for (request in coverRequests) refreshCover()
        }
    }

    /** The widget was drawn (it may lack its cover): fetch the stored song's cover if it's missing. */
    fun requestCover() {
        coverRequests.trySend(Unit)
    }

    /**
     * A relayed Pause or Next found no playback service ([com.stash.core.media.widget.WidgetKeyRelay]:
     * a crash skipped onDestroy and left the widget showing Pause). Marks the song paused and redraws
     * every widget, whose Play then starts the service again. [onDone] runs when that's finished.
     */
    fun redrawAfterLostService(onDone: () -> Unit) {
        scope.launch {
            try {
                apply(Op.Stopped)
                redraw() // even when nothing changed: the picture on the home screen is the stale one
            } finally {
                onDone()
            }
        }
    }

    override fun publish(state: NowPlayingWidgetState) {
        ops.trySend(Op.Publish(state))
    }

    override fun serviceStopped() {
        ops.trySend(Op.Stopped)
    }

    private suspend fun apply(op: Op): Boolean = try {
        when (op) {
            is Op.Publish -> store.publish(op.state)
            Op.Stopped -> store.markPaused()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "widget update not stored: ${e.javaClass.simpleName}")
        false
    }

    /** Fetches the song's cover for placed widgets; again while the song changes under a fetch. */
    private suspend fun refreshCover() {
        try {
            if (!store.hasPlacedWidgets()) return
            while (true) {
                when (store.refreshCover()) {
                    CoverRefresh.LOADED -> return redraw()
                    CoverRefresh.STALE -> continue
                    CoverRefresh.UP_TO_DATE, CoverRefresh.UNAVAILABLE -> return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget cover not refreshed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun redraw() {
        try {
            // No-op without a placed widget. A running Glance session also follows the store's flow.
            NowPlayingWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget redraw failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "StashWidget"
    }
}
