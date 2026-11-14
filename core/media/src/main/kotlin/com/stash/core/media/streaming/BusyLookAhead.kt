package com.stash.core.media.streaming

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The next song's look-ahead, when Tidal (or another lossless source) answered BUSY and the chain
 * fell back to a lossy stream: there is usually a minute of the current song still to play, so
 * the look-ahead waits out the relay's Retry-After, once, and asks the lossless sources again.
 * A FLAC answer then plays instead of the fallback, and nobody hears the wait.
 *
 * The rules, each a promise to the relay or to the listener:
 *  - Never sooner than asked: the wait runs until [StreamUrl.losslessBusyUntilMs] (the relay's
 *    Retry-After, which TidalClient never shortens), plus [GRACE_MS].
 *  - At most once: one wait, one more ask ([askAgain]); busy again, the fallback plays.
 *  - Only while there is time: the wait plus [ASK_AND_START_MS] must end before the join (the
 *    current song's end, less the crossfade, [PlayingSongClock.msBeforeJoin]). Otherwise, or as
 *    soon as a seek, a skip or a speed change makes it so, the fallback plays at once, as before.
 *  - A tapped song never waits: the song that is playing has no time to spare (the clock says
 *    so), and it falls back at once, as before.
 *
 * Only a busy fallback is ever waited for: a genuine miss (no lossless version) carries no
 * [StreamUrl.losslessBusyUntilMs] and is returned as it is.
 */
class BusyLookAhead(
    private val clock: PlayingSongClock,
    /** Wall clock, the one [StreamUrl.losslessBusyUntilMs] is in (TidalClient's). Tests drive it. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * [first], or a lossless stream for [trackId] when [first] is a busy fallback ([StreamUrl.forThisPlayOnly]),
     * this resolve is a look-ahead with time to spare, and the lossless sources ([askAgain]) serve
     * once the busy time has passed. Suspends for the wait; cancelled with the caller.
     */
    suspend fun settle(trackId: Long, first: StreamUrl?, askAgain: suspend () -> StreamUrl?): StreamUrl? {
        val busyUntil = first?.losslessBusyUntilMs ?: return first
        val beforeJoin = clock.msBeforeJoin(trackId) ?: run {
            Log.i(TAG, "track $trackId: lossless source busy and this song is playing now; the fallback plays")
            return first
        }
        val waitMs = (busyUntil - nowMs()).coerceAtLeast(0L) + GRACE_MS
        if (waitMs + ASK_AND_START_MS > beforeJoin) {
            Log.i(TAG, "track $trackId: lossless source busy for ${waitMs / 1000} s, ${beforeJoin / 1000} s before it starts; the fallback plays")
            return first
        }
        Log.i(TAG, "track $trackId: lossless source busy; the look-ahead waits ${waitMs / 1000} s (${beforeJoin / 1000} s before it starts) and asks once more")
        if (!clock.awaitAhead(trackId, waitMs, ASK_AND_START_MS)) {
            Log.i(TAG, "track $trackId: no time left to wait (skipped to, or the join moved); the fallback plays")
            return first
        }
        val again = withTimeoutOrNull(ASK_MS) { askAgain() }
        if (again == null) {
            Log.i(TAG, "track $trackId: asked once more, still no lossless answer; the fallback plays")
            return first
        }
        Log.i(TAG, "track $trackId: asked once more after the wait: ${again.origin} ${again.codec} plays")
        return again
    }

    companion object {
        private const val TAG = "BusyLookAhead"

        /** Past the relay's own time: its clock and ours are not the same clock. */
        const val GRACE_MS = 500L

        /** The one more ask: a search and a stream link from the relay (about a second; this much at most). */
        const val ASK_MS = 8_000L

        /** [ASK_MS], and the stream opening and buffering its first seconds before the join. */
        const val ASK_AND_START_MS = ASK_MS + 5_000L
    }
}
