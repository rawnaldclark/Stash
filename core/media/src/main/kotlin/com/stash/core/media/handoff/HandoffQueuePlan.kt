package com.stash.core.media.handoff

import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track

/**
 * A continued queue for [com.stash.core.media.PlayerRepository.restoreHandoff] (spec §8.3): [first] starts at once (the
 * current song, and without shuffle the next few after it), then [rest] matches everything else in the background and the
 * player puts it around [first] without interrupting it.
 */
class HandoffQueuePlan(
    val first: List<Track>,
    val positionMs: Long,
    val source: PlaybackSource.Handoff,
    val repeat: RepeatMode,
    val rest: suspend () -> HandoffQueueRest?,
    /** Called once the rest is in, with how many of its songs the player couldn't take (unavailable here). */
    val onFilled: (droppedByPlayer: Int) -> Unit = {},
)

/**
 * The songs before and after [HandoffQueuePlan.first] in timeline order. With shuffle, [playOrder] is the play order as
 * indexes into `before + first + after` (the timeline), so turning shuffle off gives back the unshuffled order.
 */
class HandoffQueueRest(
    val before: List<Track>,
    val after: List<Track>,
    val playOrder: List<Int>?,
)
