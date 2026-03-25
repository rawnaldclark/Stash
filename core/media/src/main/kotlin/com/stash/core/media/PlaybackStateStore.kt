package com.stash.core.media

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last playback position so the app can resume where the user left off.
 * This is a stub -- full DataStore-backed implementation comes in Task 5.
 */
@Singleton
class PlaybackStateStore @Inject constructor() {

    /**
     * Saves the current playback position for later restoration.
     *
     * @param trackId    ID of the track that was playing.
     * @param positionMs Playback position in milliseconds.
     * @param queueIndex Index of the track within the current queue.
     */
    suspend fun savePosition(trackId: Long, positionMs: Long, queueIndex: Int) {
        // TODO: implement with DataStore in Task 5
    }
}
