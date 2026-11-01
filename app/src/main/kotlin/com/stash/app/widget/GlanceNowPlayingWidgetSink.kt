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
 * ([NowPlayingWidgetStore], with its cover when a widget is placed), then every placed widget is
 * redrawn once. Pushes that arrive while one is being handled are applied together and redrawn
 * once. Nothing is polled; with no widget on the home screen a push costs one small file write.
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (op in ops) {
                var changed = apply(op)
                while (true) {
                    val next = ops.tryReceive().getOrNull() ?: break
                    changed = apply(next) || changed
                }
                if (changed) redraw()
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
            is Op.Publish -> store.publish(op.state, fetchCover = store.hasPlacedWidgets())
            Op.Stopped -> store.markPaused()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "widget update not stored: ${e.javaClass.simpleName}")
        false
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
