package com.stash.core.data.prefs

/**
 * Every action Now Playing offers for the current song. Each one lives either in the top row
 * (pinned) or in the ⋮ menu; the user moves them from inside the menu.
 *
 * Declaration order is the canonical order: pinned icons appear in the top row in this order
 * (with [QUEUE] held at the far right), and unpinned ones list in the menu in this order. The
 * names are persisted, so renaming one drops it from saved pins (it is ignored, not a crash):
 * add new values freely, but rename or remove with care.
 */
enum class NowPlayingAction {
    RADIO,
    LYRICS,
    QUEUE,
    LISTEN_TOGETHER,
    SAVE_TO_PLAYLIST,
    SHARE,
    POST_TO_COMMUNITY,
    VIEW_ALBUM,
    PLAYBACK_SPEED,
    DOWNLOAD,
    FLAG_WRONG_MATCH,
    ;

    companion object {
        /** A fresh install's top row: Radio · ⋮ · Queue. */
        val DefaultPins: Set<NowPlayingAction> = setOf(RADIO, QUEUE)

        /**
         * How many actions fit the top row on a small (360dp) phone beside Dismiss, Cast and ⋮
         * at a still-tappable size: the two defaults plus three.
         */
        const val MAX_PINS = 5

        /**
         * Reads persisted pin names. Null (never saved) is the default row; unknown names (a
         * value removed in a later release, or a hand-edited file) are skipped; more than
         * [MAX_PINS] (only possible from a corrupt file) keeps the first in canonical order.
         */
        fun parsePins(stored: Set<String>?): Set<NowPlayingAction> {
            if (stored == null) return DefaultPins
            return entries.filter { it.name in stored }.take(MAX_PINS).toSet()
        }
    }
}
