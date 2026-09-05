package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, monotonic-TTL cache for resolved stream URLs, keyed by track id.
 *
 * Two design properties matter, both learned the hard way:
 *
 * 1. **Bounded (LRU).** A long shuffle session streams hundreds of distinct
 *    tracks; an unbounded map would accumulate one entry per track id for
 *    the life of the process and never release them (signed URLs are only
 *    ~1 h-lived, so the entries are pure waste once stale). The cache is
 *    capped at [MAX_ENTRIES] and evicts least-recently-used entries — the
 *    working set (current queue + prefetch window) is tens of tracks, so
 *    the cap is never a functional limit, only a memory ceiling.
 *
 * 2. **Monotonic expiry.** Each [StreamUrl.expiresAtMs] is an ABSOLUTE
 *    wall-clock instant baked into the CDN signature (`etsp`). Deciding
 *    validity by comparing that against `System.currentTimeMillis()` makes
 *    the cache hostage to the device wall clock: an NTP correction, a
 *    timezone change, or a manual clock change could make a dead URL look
 *    valid (→ a 403 the user sees as "won't play") or evict a good one
 *    early. Instead we convert the server's absolute expiry into a
 *    DURATION once at insert (using the wall clock that one time) and then
 *    count it down against a MONOTONIC clock ([elapsedMsProvider],
 *    `System.nanoTime()`), which no clock change can perturb. The returned
 *    [StreamUrl] still carries the original absolute `expiresAtMs` for
 *    downstream callers (e.g. the prefetch freshness check) — only the
 *    cache's own serve/evict decision is monotonic.
 *
 * Concurrency: Media3 reads URLs on a player thread while UI/ViewModel
 * code writes on main; tests drive the clocks on the calling thread. All
 * access goes through `synchronized(lock)` over a single access-ordered
 * [LinkedHashMap], which gives both the LRU recency update on read and
 * atomic per-key reads/writes.
 *
 * The [nowMsProvider] / [elapsedMsProvider] seams exist so tests can drive
 * both clocks deterministically without `Thread.sleep`. They're
 * `internal var` so test code can swap them without going through the
 * constructor — keeping the constructor parameter-free avoids Hilt's
 * "multiple @Inject constructors" error that Kotlin default values trigger.
 */
@Singleton
class StreamUrlCache @Inject constructor() {
    /** Wall clock — used ONCE per entry, at insert, to derive its TTL. */
    internal var nowMsProvider: () -> Long = System::currentTimeMillis

    /**
     * Monotonic clock for the cache's own validity window. `System.nanoTime()`
     * is monotonic on both the JVM and Android (CLOCK_MONOTONIC) and, unlike
     * `SystemClock.elapsedRealtime()`, carries no Android-framework dependency
     * so this stays a plain-JVM-testable class.
     */
    internal var elapsedMsProvider: () -> Long = { System.nanoTime() / 1_000_000L }

    private class Entry(val url: StreamUrl, val expiryElapsedMs: Long)

    private val lock = Any()

    private val cache = object : LinkedHashMap<Long, Entry>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    fun get(trackId: Long): StreamUrl? = synchronized(lock) {
        val entry = cache[trackId] ?: return null
        if (elapsedMsProvider() < entry.expiryElapsedMs) {
            entry.url
        } else {
            cache.remove(trackId)
            null
        }
    }

    fun put(trackId: Long, url: StreamUrl) {
        // Convert the server's absolute expiry into a remaining-lifetime
        // duration using the wall clock this once, then anchor it to the
        // monotonic clock. A non-positive TTL means the URL is already
        // expired by the wall clock at insert — don't cache a dead entry.
        val ttlMs = url.expiresAtMs - nowMsProvider()
        if (ttlMs <= 0L) {
            synchronized(lock) { cache.remove(trackId) }
            return
        }
        val entry = Entry(url, expiryElapsedMs = elapsedMsProvider() + ttlMs)
        synchronized(lock) { cache[trackId] = entry }
    }

    fun invalidate(trackId: Long) {
        synchronized(lock) { cache.remove(trackId) }
    }

    /**
     * Drop every entry. Entries are keyed by track id alone, so a URL minted for one
     * streaming tier would otherwise be served for up to its TTL after the effective
     * tier changed — see [StreamQualityPolicy.streamingTier], the one caller.
     */
    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    private companion object {
        /**
         * LRU ceiling. The functional working set (active queue + prefetch
         * window) is tens of entries; this is a memory bound, not a
         * behavioural one. Each entry is a small [StreamUrl] (~hundreds of
         * bytes), so 512 caps the cache well under ~200 KB.
         */
        private const val MAX_ENTRIES = 512
    }
}
