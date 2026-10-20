package com.stash.core.data.social

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.prefs.LikePreferences
import com.stash.core.data.social.spotify.SpotifyRateLimitException
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * v0.9.52: single entry point for the heart button. Both heart entry
 * points (NowPlayingViewModel.onLikeTap, StashPlaybackService
 * COMMAND_TOGGLE_LIKE) call [setLiked] instead of touching
 * StashLikedPlaylistRepository directly.
 *
 * Contract:
 *  1. Local first, always. The Stash like/unlike runs synchronously in
 *     the caller's coroutine and its failure propagates (callers keep
 *     their existing snackbar + optimistic-rollback UX). Local state
 *     never depends on external success — hearts work offline.
 *  2. Mirroring is fire-and-forget. When a `mirror_likes_*` pref is on,
 *     an op is enqueued onto a single-consumer queue; the drain loop is
 *     the pacing mechanism (serialized, [minGapMs] between external
 *     bursts, Spotify Retry-After honored). Deliberately not a token
 *     bucket — one slow writer defangs multi-select bursts and
 *     heart-spam by construction.
 *  3. Prefs AND the track row are re-read at fire time, not enqueue
 *     time. That's what makes rapid like→unlike→like converge: the
 *     dispatcher's `*_saved_at` guards see the columns as the previous
 *     op left them, and a mid-queue toggle-off drops remaining ops.
 *  4. Failures are best-effort: log + leave dedup column untouched →
 *     organic retry on the next heart of that track (same recovery as
 *     AutoSaveScrobbler). At most ONE [mirrorFailures] emission per
 *     process lifetime — no nagging.
 *
 * Forward-only by design (no backfill of pre-existing likes — bulk
 * writes are the most bot-shaped action). A future opt-in trickle
 * backfill can reuse this queue by enqueueing (trackId, liked=true)
 * ops; the seam is already here.
 *
 * App-lifetime scope is instantiated inline like AutoSaveScrobbler /
 * LosslessUrlPrefetcher — no Hilt ApplicationScope qualifier exists in
 * this codebase. The internal constructor is the test seam (TestScope +
 * virtual-time minGap).
 */
@Singleton
class LikeCoordinator internal constructor(
    private val stashLikedRepository: StashLikedPlaylistRepository,
    private val likePreferences: LikePreferences,
    private val dispatcher: LikeDestinationDispatcher,
    private val trackDao: TrackDao,
    private val crossPlatformResolver: CrossPlatformLikeResolver,
    scope: CoroutineScope,
    private val minGapMs: Long,
) {
    @Inject constructor(
        stashLikedRepository: StashLikedPlaylistRepository,
        likePreferences: LikePreferences,
        dispatcher: LikeDestinationDispatcher,
        trackDao: TrackDao,
        crossPlatformResolver: CrossPlatformLikeResolver,
    ) : this(
        stashLikedRepository,
        likePreferences,
        dispatcher,
        trackDao,
        crossPlatformResolver,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        MIN_GAP_MS_DEFAULT,
    )

    private data class MirrorOp(val trackId: Long, val liked: Boolean)

    private val queue = Channel<MirrorOp>(capacity = Channel.UNLIMITED)
    private val failureSignalled = AtomicBoolean(false)

    private val _mirrorFailures = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One subtle "couldn't sync — will retry" per session; UI collects this. */
    val mirrorFailures: SharedFlow<String> = _mirrorFailures

    init {
        scope.launch { drainLoop() }
    }

    /**
     * Toggle the heart for [trackId]. [trackId] must be a real Room PK —
     * callers already resolve synthetic/streaming ids (ensureTrackPersisted
     * / resolveTrackIdForLike) before calling.
     */
    suspend fun setLiked(trackId: Long, liked: Boolean) {
        if (liked) stashLikedRepository.add(trackId) else stashLikedRepository.remove(trackId)

        // Fast-path gate so a mirroring-off install never churns the queue.
        // The drain loop re-checks at fire time anyway (see class KDoc #3).
        if (!likePreferences.mirrorLikesSpotifyNow() &&
            !likePreferences.mirrorLikesYtMusicNow() &&
            !likePreferences.mirrorLikesLastFmNow()
        ) {
            return
        }
        queue.send(MirrorOp(trackId, liked))
    }

    private suspend fun drainLoop() {
        for (op in queue) {
            runCatching { process(op) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "mirror op crashed for track ${op.trackId}", e)
                }
        }
    }

    private suspend fun process(op: MirrorOp) {
        val mirrorSpotify = likePreferences.mirrorLikesSpotifyNow()
        val mirrorYt = likePreferences.mirrorLikesYtMusicNow()
        val mirrorLastFm = likePreferences.mirrorLikesLastFmNow()
        var track = trackDao.getById(op.trackId)?.toDomain() ?: return

        // Cross-platform backfill (LIKE only): a heart should reach BOTH
        // enabled services even when the track was only known to one. If a
        // mirror target is on but the track lacks that platform's id, resolve
        // it (search + confidence-gate + persist) so the destination fires.
        // Skipped on unlike — we can't have saved to a platform we never
        // resolved. Best-effort: a failed/ambiguous match just means that one
        // destination is skipped this time, exactly as before.
        // A cloud song (the user's own Dropbox / OneDrive file) is never searched for on Spotify or
        // YouTube: that would write an online id onto it, the first step of matching it. It still
        // reaches Last.fm, which goes by artist and title.
        if (op.liked && track.source != com.stash.core.model.MusicSource.CLOUD) {
            if (mirrorSpotify && track.spotifyUri == null) {
                crossPlatformResolver.ensureSpotifyUri(track)?.let { track = track.copy(spotifyUri = it) }
            }
            if (mirrorYt && track.youtubeId == null) {
                crossPlatformResolver.ensureYoutubeId(track)?.let { track = track.copy(youtubeId = it) }
            }
        }

        val destinations = buildSet {
            if (mirrorSpotify && track.spotifyUri != null) add(Destination.SPOTIFY)
            if (mirrorYt && track.youtubeId != null) add(Destination.YT_MUSIC)
            // No id precondition: Last.fm matches on artist + title, so unlike the
            // other two there is nothing to resolve and nothing to skip for.
            if (mirrorLastFm) add(Destination.LAST_FM)
        }
        if (destinations.isEmpty()) return

        val verb = if (op.liked) "like" else "unlike"
        val results = if (op.liked) {
            dispatcher.like(track, destinations)
        } else {
            dispatcher.unlike(track, destinations)
        }

        var anyFailed = false
        results.forEach { (dest, result) ->
            result.onFailure { e ->
                anyFailed = true
                Log.w(TAG, "mirror $verb → $dest failed for track ${track.id}: ${e.message}")
            }
        }
        if (anyFailed && failureSignalled.compareAndSet(false, true)) {
            _mirrorFailures.tryEmit("Couldn't sync your like — will retry")
        }

        // Pacing: min gap between bursts; a 429's Retry-After stretches it —
        // but CAPPED. Spotify hands out absurd Retry-Afters (observed 86400s =
        // 24h); obeying that verbatim froze the single drain loop — and every
        // OTHER queued op, including YT — for a day. Cap at [MAX_BACKOFF_MS]:
        // the failed op retries organically on the track's next heart anyway
        // (dedup column left untouched), so there's nothing to gain by
        // sleeping the whole queue past a short cooldown.
        val retryAfterMs = results.values
            .mapNotNull { (it.exceptionOrNull() as? SpotifyRateLimitException)?.retryAfterSeconds }
            .maxOrNull()
            ?.times(1_000L) ?: 0L
        delay(minOf(maxOf(minGapMs, retryAfterMs), MAX_BACKOFF_MS))
    }

    companion object {
        private const val TAG = "LikeCoordinator"
        private const val MIN_GAP_MS_DEFAULT = 1_500L

        /**
         * Ceiling on the post-op sleep, no matter how large a 429 Retry-After
         * is. Keeps one rate-limited destination from stalling the whole
         * mirror queue for hours; the op re-fires on the track's next heart.
         */
        private const val MAX_BACKOFF_MS = 5 * 60 * 1_000L
    }
}
