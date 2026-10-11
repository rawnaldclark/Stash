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
    /** The current track is downloaded: a pinned Download then only shows that, see [isInertPinnedTap]. */
    val isDownloaded: Boolean = false,
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

/**
 * The ⋮ menu's rows, in canonical order: everything available and not in the top row. That is
 * the unpinned actions, plus pins with no room in the row this frame ([overflow], see
 * [fitTopRow]), plus "Remove download" for a downloaded track even while Download is pinned:
 * the pinned slot never deletes (see [isInertPinnedTap]), so removal stays a labelled row here.
 */
internal fun menuActions(
    pins: Set<NowPlayingAction>,
    ctx: ActionContext,
    overflow: List<NowPlayingAction> = emptyList(),
): List<NowPlayingAction> = NowPlayingAction.entries.filter { action ->
    isActionAvailable(action, ctx) &&
        (action !in pins || action in overflow || (action == NowPlayingAction.DOWNLOAD && ctx.isDownloaded))
}

/**
 * True when tapping the pinned [action] must do nothing destructive: a pinned Download on a track
 * that is already downloaded. Its slot shows the downloaded state and a tap only says so; a
 * check icon among small icons reads as a status, and one stray tap must never delete a file
 * whose source may since have gone. Removing stays a labelled row in the ⋮ menu.
 */
internal fun isInertPinnedTap(action: NowPlayingAction, ctx: ActionContext): Boolean =
    action == NowPlayingAction.DOWNLOAD && ctx.isDownloaded

/** A slot's preferred width, and the least it may shrink to (still a comfortable touch target). */
internal const val SLOT_PREFERRED_DP = 48f
internal const val SLOT_MIN_DP = 44f

/** Cast keeps Material's full 48dp interactive size. */
internal const val CAST_SLOT_DP = 48f

/**
 * The top row as it fits [widthDp] this frame: [row] is what renders, at [slotDp] per slot;
 * [overflow] holds pins with no room, which the ⋮ menu lists as normal rows for now.
 */
internal data class TopRowFit(
    val row: TopRowActions,
    val overflow: List<NowPlayingAction>,
    val slotDp: Float,
)

/**
 * Fits the pinned row into [widthDp] (the row's own width, inside the screen's side padding).
 *
 * Dismiss, ⋮, a pinned Queue and (when [castVisible]) Cast at its own 48dp are never dropped.
 * The other pins take a slot each, as many as fit at [SLOT_MIN_DP], in canonical order; the
 * rest overflow from the lowest priority (the end of the canonical order) first. Slots then
 * share the width up to [SLOT_PREFERRED_DP]. Nothing is ever unreachable: an overflowed pin is
 * a row in the ⋮ menu until the screen has room again (a wider window, a smaller display size).
 */
internal fun fitTopRow(row: TopRowActions, widthDp: Float, castVisible: Boolean): TopRowFit {
    val castWidth = if (castVisible) CAST_SLOT_DP else 0f
    val fixedSlots = 2 + row.trailing.size // Dismiss, ⋮, and Queue when pinned
    val room = widthDp - castWidth
    var fits = row.leading.size
    while (fits > 0 && (fixedSlots + fits) * SLOT_MIN_DP > room) fits--
    val slot = (room / (fixedSlots + fits)).coerceIn(SLOT_MIN_DP, SLOT_PREFERRED_DP)
    return TopRowFit(
        row = TopRowActions(leading = row.leading.take(fits), trailing = row.trailing),
        overflow = row.leading.drop(fits),
        slotDp = slot,
    )
}

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

/** The editor's note for a pin that is in the ⋮ menu only because this screen is too narrow. */
internal const val OVERFLOW_NOTE = "In the menu for now: no room on this screen"

/**
 * True when the row can take no more pins (the editor dims the empty pins and says why). The
 * cap itself is enforced in storage, inside the toggle's DataStore edit
 * ([com.stash.core.data.prefs.NowPlayingPreference.togglePin]).
 */
internal fun pinsFull(pins: Set<NowPlayingAction>): Boolean = pins.size >= NowPlayingAction.MAX_PINS
