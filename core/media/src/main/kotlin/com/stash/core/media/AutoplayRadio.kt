package com.stash.core.media

import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode

/**
 * Whether the player should seed a song radio from the current track right
 * now: the setting is on, nothing else keeps the music going (repeat, a
 * running station, library shuffle), the current track is the LAST one in
 * the queue and is playing, and it has not been tried yet ([triedTrackId]) so a station
 * that cannot be built is not retried on every state tick.
 */
internal fun shouldAutoplayRadio(
    enabled: Boolean,
    radioActive: Boolean,
    libraryShuffleActive: Boolean,
    state: PlayerState,
    triedTrackId: Long?,
): Boolean {
    if (!enabled || radioActive || libraryShuffleActive) return false
    // Only while playing: the cold-start restore parks the last session paused,
    // and that must not build a station before anyone presses play.
    if (!state.isPlaying) return false
    if (state.repeatMode != RepeatMode.OFF) return false
    val track = state.currentTrack ?: return false
    if (state.queue.isEmpty() || state.currentIndex != state.queue.lastIndex) return false
    return track.id != triedTrackId
}
