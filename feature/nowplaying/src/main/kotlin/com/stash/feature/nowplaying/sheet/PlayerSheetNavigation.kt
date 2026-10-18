package com.stash.feature.nowplaying.sheet

import com.stash.core.media.service.StashPlaybackService

/**
 * Closes the player when the app navigates while it is open (an artist from Now Playing, a link,
 * a tab switch from the snackbar): the new page opens behind the closing player, and Back from it
 * returns to the page before, not to the player.
 *
 * The first destination change is the NavHost setting its graph (a restored back stack included),
 * which must not close a player restored open; every later one does.
 */
class CollapseOnNavigation(private val sheet: PlayerSheetState) {
    private var first = true

    /** Called for every NavController destination change. */
    fun onDestinationChanged() {
        if (first) {
            first = false
            return
        }
        if (sheet.targetAnchor != PlayerSheetAnchor.MINI) sheet.collapse()
    }
}

/** The media notification's way into the full player. */
object PlayerSheetDeepLink {
    /** MainActivity's pending deep-link target for "open the full player". */
    const val TARGET = "player"

    /** The deep-link target for a launch intent's [action], or null when it isn't the notification's. */
    fun targetFor(action: String?): String? =
        if (action == StashPlaybackService.ACTION_OPEN_PLAYER) TARGET else null

    /** Opens the player for [target]; false (nothing done) for any other target. */
    fun handle(target: String?, sheet: PlayerSheetState): Boolean {
        if (target != TARGET) return false
        sheet.expand()
        return true
    }
}
