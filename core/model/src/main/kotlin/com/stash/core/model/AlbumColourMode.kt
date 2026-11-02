package com.stash.core.model

/**
 * Settings › Appearance › Album colours: whether the app takes the playing song's colours.
 *
 * - [FOLLOW_SONG] — the default: buttons, switches, highlights, the mini player, Now Playing and
 *   Home's glow wear the current album's colour (paused included); neutral with nothing playing.
 * - [NEUTRAL] — the app stays black, white and grey whatever plays (and covers are not read for it).
 *
 * Stored by name, so a later choice (a fixed colour of the user's) can join without breaking it.
 */
enum class AlbumColourMode {
    FOLLOW_SONG,
    NEUTRAL,
}
