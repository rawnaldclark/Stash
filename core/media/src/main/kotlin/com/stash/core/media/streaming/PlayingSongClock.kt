package com.stash.core.media.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the playing song is, for code that runs off the player's thread: how long until the
 * next song must start (the join), and which song is playing.
 *
 * The playback service publishes a snapshot on the application thread at every player event
 * (a transition, a seek, play or pause, a speed change, the length becoming known) and when the
 * crossfade hands over; between events the position is extrapolated, which is exact while
 * nothing changes. A placeholder's resolve reads it ([BusyLookAhead]): a song resolved while
 * another plays is a look-ahead, with the rest of that song to spare; the song that IS playing
 * (a tap, a skip to it) has none.
 *
 * Every read is lock-free (one volatile snapshot). Nothing here touches the player.
 */
@Singleton
class PlayingSongClock @Inject constructor() {

    /** One publish. [trackId] null: nothing is playing or its song is not known. */
    private data class Snapshot(
        val trackId: Long?,
        val positionMs: Long,
        /** The song's length; 0 or less while unknown. */
        val durationMs: Long,
        val playing: Boolean,
        val speed: Float,
        /** How much sooner than the song's end the next one starts (the crossfade's length, when on). */
        val joinLeadMs: Long,
        /** [elapsedMs] when published. */
        val atMs: Long,
    )

    private val snapshot = MutableStateFlow<Snapshot?>(null)

    /** Monotonic clock (no wall-clock jumps); tests drive it. */
    internal var elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * The player's state now. Application thread (where the player is read), but any thread is
     * safe. [durationMs] may be `C.TIME_UNSET` or 0 while the length is unknown.
     */
    fun publish(trackId: Long?, positionMs: Long, durationMs: Long, playing: Boolean, speed: Float, joinLeadMs: Long) {
        snapshot.value = Snapshot(trackId, positionMs.coerceAtLeast(0L), durationMs, playing, speed, joinLeadMs.coerceAtLeast(0L), elapsedMs())
    }

    /** Nothing plays any more (the player was released). */
    fun clear() {
        snapshot.value = null
    }

    /**
     * Real milliseconds before [trackId] must start, when it is being resolved AHEAD of the playing
     * song: the rest of that song at its speed, less the crossfade. Null when [trackId] is the song
     * that is playing (a tap, a skip to it: no time to spare), or when nothing is known about the
     * playing song (none, its length unknown). Paused, the rest is counted as if it played on now:
     * the listener may press play at any moment.
     */
    fun msBeforeJoin(trackId: Long): Long? = snapshot.value?.msBeforeJoin(trackId, elapsedMs())

    private fun Snapshot.msBeforeJoin(id: Long, nowMs: Long): Long? {
        if (trackId == null || trackId == id || durationMs <= 0L) return null
        val rate = speed.takeIf { it > 0f } ?: 1f
        val position = if (playing) positionMs + ((nowMs - atMs) * rate).toLong() else positionMs
        val songLeftMs = ((durationMs - position).coerceAtLeast(0L) / rate).toLong()
        return (songLeftMs - joinLeadMs).coerceAtLeast(0L)
    }

    /**
     * Waits [waitMs] for a look-ahead of [trackId], unless it can no longer afford to: [trackId]
     * starts playing (the listener skipped to it), or a seek, a speed change or a new song leaves
     * less than the rest of the wait plus [thenNeedMs] before the join. True when the whole wait
     * ran; false when it stopped early (the caller then falls back at once). Re-checked at each
     * publish: between publishes the time left shrinks exactly as the wait runs down.
     */
    suspend fun awaitAhead(trackId: Long, waitMs: Long, thenNeedMs: Long): Boolean {
        val endsAtMs = elapsedMs() + waitMs
        // Unit when it stopped early, null when the whole wait ran (a cleared snapshot is a stop too).
        val stoppedEarly = withTimeoutOrNull(waitMs) {
            snapshot.first { snap ->
                val left = snap?.msBeforeJoin(trackId, elapsedMs())
                left == null || left < (endsAtMs - elapsedMs()).coerceAtLeast(0L) + thenNeedMs
            }
            Unit
        }
        return stoppedEarly == null
    }
}
