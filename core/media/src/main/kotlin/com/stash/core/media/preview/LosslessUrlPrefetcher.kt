package com.stash.core.media.preview

import android.util.Log
import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lossless-source matches for the search preview, keyed by videoId so
 * [SearchPreviewMediaSource] can reuse a result instead of resolving twice.
 *
 * The visible-row prefetch ([warmUp], called from
 * `LaunchedEffect(track.videoId)` in search/artist-profile rows) is currently
 * a no-op — see its KDoc. 5-min TTL on cached results — Qobuz signed URLs
 * expire in minutes, and a stale entry on tap would 403 mid-stream.
 *
 * Memory bounded by TTL: stale entries get evicted via [cancelStale] on insert.
 */
@Singleton
class LosslessUrlPrefetcher internal constructor(
    private val registry: LosslessSourceRegistry,
    /** Unused while [warmUp] is a no-op; it gated the warm-up to the user's own account. */
    @Suppress("unused") private val availability: LosslessAvailability,
    private val losslessPrefs: LosslessSourcePreferences,
    /** Where warm-ups and lookups run; tests pass Unconfined or a test dispatcher. */
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    @Inject
    constructor(
        registry: LosslessSourceRegistry,
        availability: LosslessAvailability,
        losslessPrefs: LosslessSourcePreferences,
    ) : this(registry, availability, losslessPrefs, Dispatchers.IO)

    // App-lifetime scope. The class is @Singleton so this scope lives
    // for the entire process; no leaks. (No Hilt-provided
    // ApplicationScope qualifier exists in this codebase, so we
    // instantiate inline rather than depending on a missing binding.)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val cache = ConcurrentHashMap<String, CachedDeferred>()

    /**
     * Deliberately does nothing: no reader consumes a warm result today.
     *
     * Rows still call this as they enter composition (search, album discovery,
     * popular tracks). It used to run a lossless search plus a signed file-URL
     * request per row against the user's OWN Qobuz account — a 15-track album page
     * was 15 of each — and the only reader of those results is [lookup], whose only
     * caller is the 30-second preview path that `TrackActionsDelegate.previewTrack`
     * no longer reaches (every tap now plays the full track via
     * `PlayerRepository.playFromStream`). Every warm-up therefore expired unread
     * after [TTL_MS]: network, battery and that account's quota spent for nothing.
     * Bring the warm-up back (git history has it, including the own-account gate
     * and the LAZY-start fix) together with a live consumer of [lookup].
     */
    @Suppress("UNUSED_PARAMETER")
    fun warmUp(track: TrackItem) = Unit

    /**
     * Foreground (tap) resolve. If a warm result is already COMPLETE, return it
     * instantly. Otherwise cancel any in-flight (rate-limited) warmUp for this
     * track and start a NEW resolve that BYPASSES the rate limiter (user action,
     * mirrors streaming) — no redundant call, no token wait.
     */
    suspend fun lookup(track: TrackItem): SourceResult? {
        if (!losslessPrefs.enabledNow()) return null // Lossless switch off: preview via YouTube
        val key = track.videoId
        // Already-completed warm result → instant. `await()` on a completed deferred
        // returns immediately and is NOT experimental (unlike getCompleted()). Only
        // return here when it's actually done — never await a slow in-flight warmUp.
        cache[key]?.let { if (it.isFresh() && it.deferred.isCompleted) return it.deferred.await() }
        // Supersede a slow in-flight rate-limited warmUp so it doesn't spend a token.
        cache[key]?.deferred?.cancel()
        // The cache only grows below, so pruning here bounds it.
        cancelStale()
        val fast = scope.async {
            runCatching { registry.resolve(track.toQuery(), bypassRateLimit = true) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "foreground resolve failed for $key: ${e.message}")
                }
                .getOrNull()
        }
        cache[key] = CachedDeferred(deferred = fast, createdAt = System.currentTimeMillis())
        return fast.await()
    }

    /**
     * Evicts all cache entries whose TTL has elapsed, bounding memory across
     * long browse sessions. Called from [lookup] — the only place the cache
     * grows — rather than from a timer.
     *
     * Eviction drops the entry; it does not cancel a still-running
     * [Deferred]. Cancelling one would be unsafe: [lookup] awaits the
     * deferred it just stored, and a concurrent sweep could cancel the very
     * resolve a user is waiting on. In-flight work is bounded by the
     * resolver's own timeouts instead.
     */
    fun cancelStale() {
        val now = System.currentTimeMillis()
        val expired = cache.entries.filter { (_, c) -> now - c.createdAt > TTL_MS }
        expired.forEach { (k, _) -> cache.remove(k) }
        if (expired.isNotEmpty()) Log.d(TAG, "cancelStale: removed ${expired.size} stale entries")
    }

    /**
     * Maps [TrackItem] to the minimal [TrackQuery] the registry understands.
     * `durationMs` multiplication is done as `Double * 1_000` before the
     * `.toLong()` cast to preserve sub-second precision — the alternative of
     * `.toLong() * 1_000L` would truncate 3.7 s → 3000 ms.
     */
    private fun TrackItem.toQuery() = TrackQuery(
        artist = artist,
        title = title,
        album = album?.takeIf { it.isNotBlank() },
        isrc = null,
        durationMs = durationSeconds.takeIf { it > 0 }?.let { (it * 1_000).toLong() },
        // No explicit: a search-tab item carries no parental advisory, and unknown already
        // prefers the explicit original (QobuzCandidateMatcher.pickBest).
    )

    private data class CachedDeferred(
        val deferred: Deferred<SourceResult?>,
        val createdAt: Long,
    ) {
        fun isFresh() = System.currentTimeMillis() - createdAt < TTL_MS
    }

    companion object {
        private const val TAG = "LosslessUrlPrefetcher"

        /** Cache TTL in milliseconds. Qobuz signed URLs expire in ~5 minutes. */
        const val TTL_MS = 5 * 60 * 1_000L
    }
}
