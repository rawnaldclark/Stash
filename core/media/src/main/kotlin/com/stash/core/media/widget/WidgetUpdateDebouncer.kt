package com.stash.core.media.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collapses a burst of player events into one widget update.
 *
 * A song change alone fires a media-item transition, a timeline change, a metadata change (twice
 * when a stream's cover lands late) and a buffering → ready flip, all within a few hundred
 * milliseconds; each widget update costs a Glance session (a WorkManager job) and a RemoteViews
 * trip to the launcher. This is a trailing debounce: the state is published [quietMs] after the
 * last change, or at the latest [maxWaitMs] after the first one of a burst, so a player that keeps
 * flapping (a stall bouncing between buffering and ready) still reaches the widget. A state equal
 * to what was last published is never published again.
 *
 * Not thread-safe: the service calls it from the player's application (main) thread only.
 *
 * @param scope where the timer runs (the service's scope; cancelled with the service).
 * @param clock monotonic milliseconds (elapsedRealtime in the service, virtual time in tests).
 * @param publish receives each settled state.
 */
class WidgetUpdateDebouncer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val quietMs: Long = QUIET_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val publish: (NowPlayingWidgetState) -> Unit,
) {
    private var pending: NowPlayingWidgetState? = null
    private var lastPublished: NowPlayingWidgetState? = null
    private var burstStartedAt = 0L
    private var timer: Job? = null

    /** Offers the player's newest state; it is published once the player settles. */
    fun submit(state: NowPlayingWidgetState) {
        val now = clock()
        if (timer == null) burstStartedAt = now
        timer?.cancel()
        if (state == lastPublished) {
            // The burst came back to what the widget already shows (pause then play again): nothing to send.
            pending = null
            timer = null
            return
        }
        pending = state
        val wait = minOf(quietMs, maxWaitMs - (now - burstStartedAt)).coerceAtLeast(0L)
        timer = scope.launch {
            delay(wait)
            timer = null
            val settled = pending ?: return@launch
            pending = null
            if (settled != lastPublished) {
                lastPublished = settled
                publish(settled)
            }
        }
    }

    /**
     * Drops anything pending and forgets what was published: the service stopped, so the next
     * state (a play that lands on this same instance) must be sent even if it equals the last one.
     */
    fun reset() {
        timer?.cancel()
        timer = null
        pending = null
        lastPublished = null
    }

    companion object {
        /** How long the player must stay unchanged before its state goes out. */
        const val QUIET_MS = 350L

        /** The longest a burst can hold an update back. */
        const val MAX_WAIT_MS = 1_500L
    }
}
