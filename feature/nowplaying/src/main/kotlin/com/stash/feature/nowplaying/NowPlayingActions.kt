package com.stash.feature.nowplaying

import com.stash.core.data.prefs.NowPlayingAction

/*
 * Where each Now Playing action shows: the pure rules behind the top row, the ⋮ menu and the
 * menu's pin editor. Kept free of Compose so the visibility rules and the cap are unit-tested
 * (NowPlayingActionsTest) rather than living in `if`s scattered through the screen.
 */

/** What the screen knows that decides whether an action can show at all right now. */
internal data class ActionContext(
    /** In a Listen Together room: the room owns the queue and the pace. */
    val inRoom: Boolean = false,
    /** Playing on a Cast speaker: the speaker decodes, so speed is no longer the phone's. */
    val casting: Boolean = false,
    /** Community is on (LocalPostToCommunity is provided). */
    val communityAvailable: Boolean = false,
)

/**
 * Whether [action] can show right now, pinned or not. These are the rules the row and the
 * menu had before pins: no Radio, Queue or Speed in a room; no Speed while casting; Post to
 * Community only while Community is on.
 */
internal fun isActionAvailable(action: NowPlayingAction, ctx: ActionContext): Boolean = when (action) {
    NowPlayingAction.RADIO, NowPlayingAction.QUEUE -> !ctx.inRoom
    NowPlayingAction.PLAYBACK_SPEED -> !ctx.inRoom && !ctx.casting
    NowPlayingAction.POST_TO_COMMUNITY -> ctx.communityAvailable
    else -> true
}

/**
 * The pinned icons in the top row. [leading] sits left of ⋮ in canonical order; [trailing]
 * holds Queue, which keeps its far-right home when pinned (so the default reads Radio · ⋮ · Queue).
 */
internal data class TopRowActions(
    val leading: List<NowPlayingAction>,
    val trailing: List<NowPlayingAction>,
) {
    val size: Int get() = leading.size + trailing.size
}

internal fun topRowActions(pins: Set<NowPlayingAction>, ctx: ActionContext): TopRowActions {
    val shown = NowPlayingAction.entries.filter { it in pins && isActionAvailable(it, ctx) }
    return TopRowActions(
        leading = shown.filter { it != NowPlayingAction.QUEUE },
        trailing = shown.filter { it == NowPlayingAction.QUEUE },
    )
}

/** The ⋮ menu's rows: everything available and not pinned, in canonical order. */
internal fun menuActions(pins: Set<NowPlayingAction>, ctx: ActionContext): List<NowPlayingAction> =
    NowPlayingAction.entries.filter { it !in pins && isActionAvailable(it, ctx) }

/**
 * The pin editor's rows. Everything the user could ever use is listed, including actions a
 * room or a cast hides for now (the screen notes why), so a pin never becomes unreachable. Post
 * to Community is listed only while Community is on, unless it is still pinned from before, so
 * a pin that takes a slot can always be removed.
 */
internal fun editableActions(pins: Set<NowPlayingAction>, ctx: ActionContext): List<NowPlayingAction> =
    NowPlayingAction.entries.filter {
        it in pins || it != NowPlayingAction.POST_TO_COMMUNITY || ctx.communityAvailable
    }

/** Why a pinned action is missing from the row right now, or null when it shows. */
internal fun hiddenReason(action: NowPlayingAction, ctx: ActionContext): String? = when {
    isActionAvailable(action, ctx) -> null
    ctx.inRoom && action != NowPlayingAction.POST_TO_COMMUNITY -> "Hidden while listening together"
    ctx.casting && action == NowPlayingAction.PLAYBACK_SPEED -> "Hidden while casting"
    else -> "Not available right now"
}

/** The result of tapping a pin in the editor. */
internal sealed interface PinToggle {
    data class Changed(val pins: Set<NowPlayingAction>) : PinToggle

    /** The row already holds [NowPlayingAction.MAX_PINS]: nothing changes, the editor explains. */
    data object Full : PinToggle
}

/** Pins or unpins [action]. Unpinning always works; pinning stops at [NowPlayingAction.MAX_PINS]. */
internal fun togglePin(pins: Set<NowPlayingAction>, action: NowPlayingAction): PinToggle = when {
    action in pins -> PinToggle.Changed(pins - action)
    pins.size >= NowPlayingAction.MAX_PINS -> PinToggle.Full
    else -> PinToggle.Changed(pins + action)
}

/** True when the row can take no more pins (the editor dims the empty pins and says why). */
internal fun pinsFull(pins: Set<NowPlayingAction>): Boolean = pins.size >= NowPlayingAction.MAX_PINS
