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
    /** This phone can Cast (Play Services present: the connection is anything but Unavailable). */
    val castSupported: Boolean = false,
    /**
     * The current track is an All Songs file: played where it is, never saved to the library,
     * downloaded, flagged, shared or sent to a room (see [SCANNED_HIDDEN]).
     */
    val isScannedSong: Boolean = false,
    /** The current track is from the user's own cloud storage (see [CLOUD_HIDDEN]). */
    val isCloudSong: Boolean = false,
)

/** What an All Songs file can't do: each needs a library row, an online identity, or both. */
internal val SCANNED_HIDDEN: Set<NowPlayingAction> = setOf(
    NowPlayingAction.LISTEN_TOGETHER,
    NowPlayingAction.SAVE_TO_PLAYLIST,
    NowPlayingAction.SHARE,
    NowPlayingAction.POST_TO_COMMUNITY,
    NowPlayingAction.DOWNLOAD,
    NowPlayingAction.FLAG_WRONG_MATCH,
)

/**
 * What a cloud song (the user's own Dropbox / OneDrive file) can't do: it is the file itself,
 * so there is no "wrong match" to flag and nothing to swap it for.
 */
internal val CLOUD_HIDDEN: Set<NowPlayingAction> = setOf(NowPlayingAction.FLAG_WRONG_MATCH)

/**
 * Whether [action] can show right now, pinned or not. These are the rules the row and the
 * menu had before pins: no Radio, Queue or Speed in a room; no Speed while casting; Post to
 * Community only while Community is on; Cast exactly where its automatic button showed (a
 * phone that can Cast, outside a Listen Together room: the two exclude each other).
 */
internal fun isActionAvailable(action: NowPlayingAction, ctx: ActionContext): Boolean = when {
    ctx.isScannedSong && action in SCANNED_HIDDEN -> false
    ctx.isCloudSong && action in CLOUD_HIDDEN -> false
    else -> isActionAvailableForAnySong(action, ctx)
}

private fun isActionAvailableForAnySong(action: NowPlayingAction, ctx: ActionContext): Boolean = when (action) {
    NowPlayingAction.CAST -> ctx.castSupported && !ctx.inRoom
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

/** A pinned Cast keeps Material's full 48dp interactive size (its IconButton enforces it). */
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
 * Dismiss, ⋮ and a pinned Queue are never dropped. The other pins (Cast among them, at its own
 * [CAST_SLOT_DP]) take a slot each, as many as fit with every other slot at [SLOT_MIN_DP], in
 * canonical order; the rest overflow from the lowest priority (the end of the canonical order)
 * first. Slots then share the width up to [SLOT_PREFERRED_DP]. Nothing is ever unreachable: an
 * overflowed pin is a row in the ⋮ menu until the screen has room again (a wider window, a
 * smaller display size). [extraSlots] are other fixed buttons in the row (the full art toggle).
 */
internal fun fitTopRow(row: TopRowActions, widthDp: Float, extraSlots: Int = 0): TopRowFit {
    val fixedSlots = 2 + row.trailing.size + extraSlots // Dismiss, ⋮, Queue when pinned, and the toggle
    fun castWidth(shown: List<NowPlayingAction>) = if (NowPlayingAction.CAST in shown) CAST_SLOT_DP else 0f
    fun shrinkingSlots(shown: List<NowPlayingAction>) = fixedSlots + shown.count { it != NowPlayingAction.CAST }
    var fits = row.leading.size
    while (fits > 0) {
        val shown = row.leading.take(fits)
        if (castWidth(shown) + shrinkingSlots(shown) * SLOT_MIN_DP <= widthDp) break
        fits--
    }
    val shown = row.leading.take(fits)
    val slot = ((widthDp - castWidth(shown)) / shrinkingSlots(shown)).coerceIn(SLOT_MIN_DP, SLOT_PREFERRED_DP)
    return TopRowFit(
        row = TopRowActions(leading = shown, trailing = row.trailing),
        overflow = row.leading.drop(fits),
        slotDp = slot,
    )
}

/**
 * The pin editor's rows. Everything the user could ever use is listed, including actions a
 * room or a cast hides for now (the screen notes why), so a pin never becomes unreachable. Post
 * to Community is listed only while Community is on, and Cast only on a phone that can Cast,
 * unless still pinned from before, so a pin that takes a slot can always be removed.
 */
internal fun editableActions(pins: Set<NowPlayingAction>, ctx: ActionContext): List<NowPlayingAction> =
    NowPlayingAction.entries.filter {
        it in pins || when (it) {
            NowPlayingAction.POST_TO_COMMUNITY -> ctx.communityAvailable
            NowPlayingAction.CAST -> ctx.castSupported
            else -> true
        }
    }

/** Why a pinned action is missing from the row right now, or null when it shows. */
internal fun hiddenReason(action: NowPlayingAction, ctx: ActionContext): String? = when {
    isActionAvailable(action, ctx) -> null
    ctx.inRoom && action != NowPlayingAction.POST_TO_COMMUNITY -> "Hidden while listening together"
    ctx.isScannedSong && action in SCANNED_HIDDEN -> "Not for songs from your folders"
    ctx.isCloudSong && action in CLOUD_HIDDEN -> "Not for songs from your cloud storage"
    action == NowPlayingAction.CAST -> "Cast isn't available on this phone"
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
