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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Visible-row prefetch of lossless-source matches. Triggered by
 * `LaunchedEffect(track.videoId) { warmUp(track) }` in search/artist-
 * profile row composables. Stores `Deferred<SourceResult?>` keyed by
 * videoId so [SearchPreviewMediaSource] can join in-flight resolutions
 * without hitting the rate limiter twice.
 *
 * Concurrency cap of 4 matches the aggregator's burst-of-4 budget;
 * `AggregatorRateLimiter`'s 1-token-per-3s budget then naturally
 * serialises beyond that. 5-min TTL on cached results — Qobuz signed
 * URLs expire in minutes, and a stale entry on tap would 403 mid-stream.
 *
 * Memory bounded by TTL: stale entries get evicted via [cancelStale]
 * which `StashApplication` invokes every 60s.
 */
@Singleton
class LosslessUrlPrefetcher internal constructor(
    private val registry: LosslessSourceRegistry,
    private val availability: LosslessAvailability,
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
    private val concurrency = Semaphore(MAX_CONCURRENT)

    /**
     * Warms up the lossless URL resolution for [track] in the background.
     * Idempotent — if a fresh [CachedDeferred] already exists for this
     * videoId the call is a no-op, so composable rows may call this on
     * every recomposition without triggering duplicate network requests.
     */
    fun warmUp(track: TrackItem) {
        val key = track.videoId
        cache[key]?.let { if (it.isFresh()) return }
        // Evict here rather than on a timer. The cache only ever grows at
        // this line, so pruning on insert bounds it exactly as well as the
        // 60s loop that used to do it from StashApplication — and that loop
        // ran for the entire life of the process, waking every minute to
        // sweep a map that is empty unless the user is browsing.
        cancelStale()
        // Store the entry BEFORE its body can run (LAZY start): with an eager
        // start, a fast dispatcher ran the skip path's remove-by-key before the
        // entry was stored, and the entry then landed as a completed null that
        // the next tap read instead of resolving (the CI-only flake).
        lateinit var entry: CachedDeferred
        entry = CachedDeferred(
            deferred = scope.async(start = CoroutineStart.LAZY) {
                // Speculative work may only spend the user's OWN account. Every other
                // lossless path is a shared, capped budget — the relay's per-account
                // caps, ARCOD's daily quota — and on launch day browsing alone drained
                // both before anyone pressed play. The entry removes itself so a later
                // tap does a real resolve instead of reading a cached "skipped" null.
                // The Lossless switch is off → the preview is YouTube, never FLAC (it governs
                // streaming and downloads alike; this is the one lossless entry that does not
                // go through StreamSourceRegistry, so it checks the switch itself).
                if (!losslessPrefs.enabledNow() || !availability.ownAccountLiveNow()) {
                    cache.remove(key, entry) // only our own entry, never a newer lookup's
                    return@async null
                }
                concurrency.withPermit {
                    runCatching { registry.resolve(track.toQuery(), bypassRateLimit = false) }
                        .onFailure { e ->
                            // Re-throw cancellation so a lookup that supersedes this
                            // warmUp doesn't log a spurious "resolve failed … cancelled"
                            // (never swallow CancellationException).
                            if (e is CancellationException) throw e
                            Log.w(TAG, "resolve failed for $key: ${e.message}")
                        }
                        .getOrNull()
                }
            },
            createdAt = System.currentTimeMillis(),
        )
        cache[key] = entry
        entry.deferred.start()
    }

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
     * long browse sessions. Called from [warmUp] — the only place the cache
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
        album = null,
        isrc = null,
        durationMs = durationSeconds.takeIf { it > 0 }?.let { (it * 1_000).toLong() },
    )

    private data class CachedDeferred(
        val deferred: Deferred<SourceResult?>,
        val createdAt: Long,
    ) {
        fun isFresh() = System.currentTimeMillis() - createdAt < TTL_MS
    }

    companion object {
        private const val TAG = "LosslessUrlPrefetcher"

        /** Max concurrent in-flight registry resolutions. Matches the aggregator burst budget. */
        const val MAX_CONCURRENT = 4

        /** Cache TTL in milliseconds. Qobuz signed URLs expire in ~5 minutes. */
        const val TTL_MS = 5 * 60 * 1_000L
    }
}
