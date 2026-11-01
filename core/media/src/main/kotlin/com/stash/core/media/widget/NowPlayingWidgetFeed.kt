package com.stash.core.media.widget

import androidx.media3.common.Player
import com.stash.core.media.PlaybackResumer
import com.stash.core.model.RepeatMode
import kotlinx.coroutines.CoroutineScope

/**
 * The playback service's side of the home screen widget: listens to whichever player the
 * MediaSession holds (the master, a crossfade's new master, the cast or Listen Together wrapper) and
 * pushes its song and play state, debounced, to the [sink]. Event-driven only: nothing here runs
 * while the player is quiet, and there is no position or progress.
 *
 * Owned by the service; every call is on its main thread.
 */
class NowPlayingWidgetFeed(
    scope: CoroutineScope,
    private val sink: NowPlayingWidgetSink,
    clock: () -> Long,
) {
    private val debouncer = WidgetUpdateDebouncer(scope = scope, clock = clock, publish = sink::publish)
    private var followed: Player? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(*WIDGET_EVENTS)) push(player)
        }
    }

    /** Follows [player], the session's player from now on (call after every `session.player = …`). */
    fun follow(player: Player) {
        if (followed === player) return
        followed?.removeListener(listener)
        followed = player
        player.addListener(listener)
        push(player)
    }

    /** The service is stopping: drop pending updates and show the song paused. */
    fun stopped() {
        debouncer.reset()
        sink.serviceStopped()
    }

    /** Detaches from the player (onDestroy, after [stopped]). */
    fun release() {
        followed?.removeListener(listener)
        followed = null
        debouncer.reset()
    }

    private fun push(player: Player) {
        NowPlayingWidgetMapper.map(player.widgetSnapshot())?.let(debouncer::submit)
    }

    private companion object {
        /** The events that can change the title, artist, cover or play state. */
        val WIDGET_EVENTS = intArrayOf(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_MEDIA_METADATA_CHANGED,
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
        )
    }
}

/**
 * The resume plan for a widget Next on a stopped service: one song on from where the queue was
 * left, from its start. At the end of the queue it wraps only under repeat-all; otherwise there is
 * nothing to skip to and the song the queue was left on resumes where it stopped. Pure; see
 * [WidgetResumeSkipTest].
 */
fun PlaybackResumer.ResumePlan.skippedOne(): PlaybackResumer.ResumePlan {
    val next = startIndex + 1
    return when {
        next < tracks.size -> copy(startIndex = next, positionMs = 0L)
        repeatMode == RepeatMode.ALL && tracks.size > 1 -> copy(startIndex = 0, positionMs = 0L)
        else -> this
    }
}
