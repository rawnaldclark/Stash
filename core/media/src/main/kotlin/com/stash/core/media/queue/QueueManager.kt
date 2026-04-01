package com.stash.core.media.queue

import com.stash.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable snapshot of the current playback queue state.
 *
 * @property originalQueue  The tracks in their original insertion order.
 * @property shuffledQueue  The tracks in shuffled order (empty when [isShuffled] is false).
 * @property isShuffled     Whether shuffle mode is active.
 * @property currentIndex   Index within [activeQueue] pointing at the currently-playing track.
 */
data class QueueState(
    val originalQueue: List<Track> = emptyList(),
    val shuffledQueue: List<Track> = emptyList(),
    val isShuffled: Boolean = false,
    val currentIndex: Int = 0,
) {
    /** The queue that drives actual playback — shuffled when shuffle is on, original otherwise. */
    val activeQueue: List<Track>
        get() = if (isShuffled) shuffledQueue else originalQueue

    /** The track currently at [currentIndex], or null if the queue is empty. */
    val currentTrack: Track?
        get() = activeQueue.getOrNull(currentIndex)

    /** True when there is a track after [currentIndex] in [activeQueue]. */
    val hasNext: Boolean
        get() = currentIndex < activeQueue.size - 1

    /** True when there is a track before [currentIndex] in [activeQueue]. */
    val hasPrevious: Boolean
        get() = currentIndex > 0

    /** Number of tracks in the active queue. */
    val size: Int
        get() = activeQueue.size
}

/**
 * Manages the playback queue for Stash, including shuffle, navigation, and dynamic editing.
 *
 * All mutations are applied atomically via [MutableStateFlow.update] so that collectors
 * never observe a partially-updated state.
 *
 * Thread safety: [MutableStateFlow.update] is thread-safe; callers may invoke these methods
 * from any coroutine context.
 */
@Singleton
class QueueManager @Inject constructor() {

    private val _queueState = MutableStateFlow(QueueState())

    /**
     * Observable stream of queue state. Collect from this in ViewModels and the playback
     * service to react to queue changes.
     */
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    // ---- Queue initialisation ----------------------------------------------------------------

    /**
     * Replaces the entire queue with [tracks] and positions the cursor at [startIndex].
     *
     * If shuffle is currently enabled the new queue is immediately shuffled, keeping the
     * track at [startIndex] pinned to position 0.
     *
     * @param tracks     The ordered list of tracks to enqueue.
     * @param startIndex The index within [tracks] to begin playback from (default 0).
     * @throws IllegalArgumentException if [startIndex] is out of bounds for [tracks].
     */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        require(tracks.isEmpty() || startIndex in tracks.indices) {
            "startIndex $startIndex is out of bounds for a queue of size ${tracks.size}"
        }

        _queueState.update { current ->
            if (current.isShuffled && tracks.isNotEmpty()) {
                val shuffled = buildShuffledQueue(tracks, startIndex)
                current.copy(
                    originalQueue = tracks,
                    shuffledQueue = shuffled,
                    currentIndex = 0,
                )
            } else {
                current.copy(
                    originalQueue = tracks,
                    shuffledQueue = emptyList(),
                    currentIndex = if (tracks.isEmpty()) 0 else startIndex,
                )
            }
        }
    }

    // ---- Shuffle ----------------------------------------------------------------------------

    /**
     * Toggles shuffle mode.
     *
     * - **Enabling**: builds a shuffled queue with the current track pinned to index 0
     *   so playback continues uninterrupted.
     * - **Disabling**: restores the original queue order and seeks [currentIndex] to match
     *   the currently-playing track's position in [originalQueue].
     */
    fun toggleShuffle() {
        _queueState.update { current ->
            if (current.isShuffled) {
                // Restore original order, finding where the current track sits.
                val restoredIndex = current.currentTrack
                    ?.let { track -> current.originalQueue.indexOfFirst { it.id == track.id } }
                    ?.takeIf { it >= 0 }
                    ?: 0

                current.copy(
                    shuffledQueue = emptyList(),
                    isShuffled = false,
                    currentIndex = restoredIndex,
                )
            } else {
                // Build a new shuffle with the current track locked to slot 0.
                val shuffled = if (current.originalQueue.isEmpty()) {
                    emptyList()
                } else {
                    buildShuffledQueue(current.originalQueue, current.currentIndex)
                }

                current.copy(
                    shuffledQueue = shuffled,
                    isShuffled = true,
                    currentIndex = 0,
                )
            }
        }
    }

    // ---- Navigation -------------------------------------------------------------------------

    /**
     * Advances to the next track in the active queue.
     *
     * @return The track now at the new [currentIndex], or null if already at the end.
     */
    fun skipToNext(): Track? {
        var result: Track? = null
        _queueState.update { current ->
            if (current.hasNext) {
                val newIndex = current.currentIndex + 1
                result = current.activeQueue[newIndex]
                current.copy(currentIndex = newIndex)
            } else {
                current // no-op; already at end
            }
        }
        return result
    }

    /**
     * Moves back to the previous track in the active queue.
     *
     * @return The track now at the new [currentIndex], or null if already at the start.
     */
    fun skipToPrevious(): Track? {
        var result: Track? = null
        _queueState.update { current ->
            if (current.hasPrevious) {
                val newIndex = current.currentIndex - 1
                result = current.activeQueue[newIndex]
                current.copy(currentIndex = newIndex)
            } else {
                current // no-op; already at start
            }
        }
        return result
    }

    /**
     * Jumps directly to [index] in the active queue.
     *
     * @param index Target index. Silently ignored if out of bounds.
     */
    fun skipToIndex(index: Int) {
        _queueState.update { current ->
            if (index in current.activeQueue.indices) {
                current.copy(currentIndex = index)
            } else {
                current
            }
        }
    }

    // ---- Dynamic editing --------------------------------------------------------------------

    /**
     * Inserts [track] immediately after the currently-playing track in the active queue.
     *
     * If the queue is empty the track becomes the only (and current) item.
     * Both the active and inactive queue variants are kept in sync.
     *
     * @param track The track to insert next.
     */
    fun addNext(track: Track) {
        _queueState.update { current ->
            if (current.activeQueue.isEmpty()) {
                // Empty queue -- just set the track as the sole item.
                current.copy(
                    originalQueue = listOf(track),
                    shuffledQueue = if (current.isShuffled) listOf(track) else emptyList(),
                    currentIndex = 0,
                )
            } else {
                val insertPos = current.currentIndex + 1
                if (current.isShuffled) {
                    val newShuffled = current.shuffledQueue.toMutableList().apply {
                        add(insertPos.coerceAtMost(size), track)
                    }
                    val newOriginal = current.originalQueue + track
                    current.copy(originalQueue = newOriginal, shuffledQueue = newShuffled)
                } else {
                    val newOriginal = current.originalQueue.toMutableList().apply {
                        add(insertPos.coerceAtMost(size), track)
                    }
                    current.copy(originalQueue = newOriginal)
                }
            }
        }
    }

    /**
     * Appends [track] to the end of both the original queue and (if shuffle is active)
     * the shuffled queue, so it will eventually be reached in both modes.
     *
     * @param track The track to enqueue.
     */
    fun addToQueue(track: Track) {
        _queueState.update { current ->
            val newOriginal = current.originalQueue + track
            val newShuffled = if (current.isShuffled) current.shuffledQueue + track else current.shuffledQueue
            current.copy(originalQueue = newOriginal, shuffledQueue = newShuffled)
        }
    }

    /**
     * Removes the track at [index] from the active queue.
     *
     * The corresponding track is also removed from the inactive queue variant to keep
     * both lists consistent. [currentIndex] is adjusted if the removed track precedes it.
     *
     * @param index Index within [QueueState.activeQueue] to remove. Silently ignored if
     *              out of bounds.
     */
    fun removeFromQueue(index: Int) {
        _queueState.update { current ->
            val active = current.activeQueue
            if (index !in active.indices) return@update current

            val removedTrack = active[index]

            val newOriginal = current.originalQueue.toMutableList().apply {
                removeAll { it.id == removedTrack.id }
            }
            val newShuffled = if (current.isShuffled) {
                current.shuffledQueue.toMutableList().apply {
                    removeAt(index)
                }
            } else {
                current.shuffledQueue
            }

            // Adjust cursor: if removed item was before current, shift back by one.
            val newIndex = when {
                index < current.currentIndex -> current.currentIndex - 1
                index == current.currentIndex -> minOf(current.currentIndex, active.size - 2)
                    .coerceAtLeast(0)
                else -> current.currentIndex
            }

            current.copy(
                originalQueue = newOriginal,
                shuffledQueue = newShuffled,
                currentIndex = newIndex,
            )
        }
    }

    /**
     * Moves the track at position [from] to position [to] within the active queue.
     *
     * Both the active and inactive queue variants are kept in sync. [currentIndex] follows
     * the currently-playing track through the move.
     *
     * @param from Source index in [QueueState.activeQueue]. Silently ignored if out of bounds
     *             or equal to [to].
     * @param to   Destination index in [QueueState.activeQueue]. Clamped to valid range.
     */
    fun moveInQueue(from: Int, to: Int) {
        if (from == to) return

        _queueState.update { current ->
            val active = current.activeQueue
            if (from !in active.indices) return@update current

            val clampedTo = to.coerceIn(active.indices)
            val mutableActive = active.toMutableList()
            val moved = mutableActive.removeAt(from)
            mutableActive.add(clampedTo, moved)

            // Determine updated currentIndex after the move.
            val newCurrentIndex = when (current.currentIndex) {
                from -> clampedTo
                in (minOf(from, clampedTo)..maxOf(from, clampedTo)) -> {
                    if (from < clampedTo) current.currentIndex - 1
                    else current.currentIndex + 1
                }
                else -> current.currentIndex
            }

            if (current.isShuffled) {
                current.copy(
                    shuffledQueue = mutableActive,
                    currentIndex = newCurrentIndex,
                )
            } else {
                current.copy(
                    originalQueue = mutableActive,
                    currentIndex = newCurrentIndex,
                )
            }
        }
    }

    /**
     * Clears the queue entirely and resets all state to defaults.
     */
    fun clear() {
        _queueState.value = QueueState()
    }

    // ---- Private helpers --------------------------------------------------------------------

    /**
     * Builds a shuffled copy of [tracks] with the item at [anchorIndex] fixed to slot 0.
     *
     * All other tracks are appended in a randomly-shuffled order after the anchor.
     *
     * @param tracks      Source list to shuffle.
     * @param anchorIndex Index of the track that must appear first in the result.
     * @return A new shuffled list with the anchor track at index 0.
     */
    private fun buildShuffledQueue(tracks: List<Track>, anchorIndex: Int): List<Track> {
        val anchor = tracks[anchorIndex]
        val rest = tracks.toMutableList().apply { removeAt(anchorIndex) }
        rest.shuffle()
        return listOf(anchor) + rest
    }
}
