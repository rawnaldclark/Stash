package com.stash.core.media

import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the playback engine. Consumers observe [playerState] for UI updates
 * and call the suspend functions to control playback.
 */
interface PlayerRepository {

    /** Current snapshot of playback state, updated in real time. */
    val playerState: StateFlow<PlayerState>

    /**
     * Emits the current playback position in milliseconds at a regular interval
     * (typically ~250 ms) while playback is active.
     */
    val currentPosition: Flow<Long>

    /** Start or resume playback. */
    suspend fun play()

    /** Pause playback. */
    suspend fun pause()

    /** Skip to the next track in the queue. */
    suspend fun skipNext()

    /** Skip to the previous track (or restart current track based on position). */
    suspend fun skipPrevious()

    /** Seek to the given [positionMs] within the current track. */
    suspend fun seekTo(positionMs: Long)

    /**
     * Replace the current queue with [tracks] and begin playback at [startIndex].
     */
    suspend fun setQueue(tracks: List<Track>, startIndex: Int = 0)

    /**
     * Insert [track] immediately after the currently-playing track in the queue.
     * Playback continues uninterrupted; the inserted track will play next.
     */
    suspend fun addNext(track: Track)

    /**
     * Append [track] to the end of the current queue.
     * Playback continues uninterrupted.
     */
    suspend fun addToQueue(track: Track)

    /** Toggle shuffle mode on/off. */
    suspend fun toggleShuffle()

    /** Cycle repeat mode: OFF -> ALL -> ONE -> OFF. */
    suspend fun cycleRepeatMode()

    /**
     * Remove the track at [index] from the playback queue.
     * Silently ignored if the index is out of bounds.
     */
    suspend fun removeFromQueue(index: Int)

    /**
     * Move the track at position [from] to position [to] within the playback queue.
     * Both indices refer to the current MediaController item list.
     */
    suspend fun moveInQueue(from: Int, to: Int)

    /**
     * Jump playback to the track at [index] in the queue.
     * Starts from the beginning of that track.
     */
    suspend fun skipToQueueIndex(index: Int)
}
