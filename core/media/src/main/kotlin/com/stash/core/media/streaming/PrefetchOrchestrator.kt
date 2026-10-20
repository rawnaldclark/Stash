package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether the *next* track in the queue should have its stream
 * URL resolved and cached ahead of auto-advance, and (if so) fires the
 * resolve in [scope] without blocking the caller.
 *
 * Extracted out of [com.stash.core.media.service.StashPlaybackService] so
 * the logic is unit-testable without booting a [androidx.media3.session
 * .MediaSessionService] / [androidx.media3.exoplayer.ExoPlayer]. The
 * service holds a single [PrefetchOrchestrator] and calls
 * [onPlaybackProgress] from a periodic position-poll while playback is
 * active.
 *
 * Trigger conditions (all must hold):
 *  1. `positionMs / durationMs > 0.60` — the user is past 60 % and an
 *     auto-advance is imminent.
 *  2. A next track exists in the queue.
 *  3. The next track is **streamable** and **not already downloaded** —
 *     local files have nothing to resolve; non-streamable rows will fail
 *     the resolve and waste a Kennyy roundtrip. (Streaming is always
 *     allowed; the Download switch only decides what sync writes to disk,
 *     so it is not consulted here.)
 *  4. The next track's URL is not already in [StreamUrlCache] within
 *     TTL — duplicate resolves are wasteful.
 *  5. We have not already attempted a prefetch for this `nextTrackId` in
 *     the current play session — idempotency guard so a failing resolve
 *     doesn't spam Kennyy as the position-poll keeps firing.
 *
 * The "attempted" set is cleared by [resetSession] which the service is
 * expected to invoke on every `onMediaItemTransition` — once playback
 * moves to the next track, that track id may legitimately need a re-
 * attempt if its predecessor is repeated (REPEAT_ONE) or skipped back to.
 *
 * Failures are logged at WARN and swallowed — the user finds out about
 * an unresolvable next track when auto-advance hits [StreamRoutingResult
 * .NotAvailable] in the routing decision tree, not from a prefetch
 * snackbar.
 */
@Singleton
class PrefetchOrchestrator @Inject constructor(
    private val streamResolver: StreamSourceRegistry,
    private val streamUrlCache: StreamUrlCache,
    private val trackDao: TrackDao,
) {
    /**
     * Track ids for which a prefetch resolve has been attempted in the
     * current play session. Cleared by [resetSession]. ConcurrentHashMap-
     * backed for safety against the periodic-poll scheduling races.
     */
    private val attempted: MutableSet<Long> = Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )

    /**
     * Called by the host (service) from its periodic position poll. All
     * arguments are read off the player on the main thread; the actual
     * DAO + resolver work runs inside [scope].
     *
     * @param scope coroutine scope tied to the host's lifecycle — the
     *  service's `serviceScope` in production. Cancelling it cancels
     *  any in-flight prefetch.
     * @param nextTrackId mediaId of the next queue item parsed to Long,
     *  or null if no next item or unparseable.
     * @param positionMs current track playback position.
     * @param durationMs current track total duration; treated as
     *  "unknown" (no prefetch) when ≤ 0.
     */
    fun onPlaybackProgress(
        scope: CoroutineScope,
        nextTrackId: Long?,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (nextTrackId == null) return
        if (durationMs <= 0L) return
        val progress = positionMs.toDouble() / durationMs.toDouble()
        if (progress <= PREFETCH_THRESHOLD) return
        if (!attempted.add(nextTrackId)) return // already attempted this session

        scope.launch {
            try {
                if (streamUrlCache.get(nextTrackId) != null) return@launch

                val track = trackDao.getById(nextTrackId) ?: return@launch
                if (track.isDownloaded) return@launch
                // Cloud songs resolve their own link when opened; never the online chain.
                if (track.source == com.stash.core.model.MusicSource.CLOUD) return@launch
                // Skip only rows CONFIRMED unstreamable (checked and false).
                // Synced-library rows sit at isStreamable=false with
                // isStreamableCheckedAt=null ("never checked" — the
                // AvailabilityCheckWorker that set the flag is gone);
                // gating on the bare flag silently disabled next-track
                // prefetch for the whole synced library.
                if (!track.isStreamable && track.isStreamableCheckedAt != null) return@launch

                // speculative: never makes the shared Tidal relay search for a look-ahead.
                val resolved = streamResolver.resolve(track, speculative = true)
                if (resolved != null) {
                    streamUrlCache.put(nextTrackId, resolved)
                    Log.d(TAG, "Prefetched stream URL for track $nextTrackId")
                } else {
                    Log.w(TAG, "Prefetch resolve returned null for track $nextTrackId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed for track $nextTrackId", e)
            }
        }
    }

    /**
     * Clears the per-session "already attempted" guard. Hosts should
     * call this on every track transition so a fresh prefetch attempt
     * is allowed for the new "next" track.
     */
    fun resetSession() {
        attempted.clear()
    }

    private companion object {
        const val TAG = "StashPrefetch"

        /**
         * Pre-fetch fires once playback crosses this fraction of the
         * current track's duration. 60 % leaves ~40 % of runway to
         * absorb a slow Kennyy resolve before the user hits auto-
         * advance.
         */
        const val PREFETCH_THRESHOLD = 0.60
    }
}
