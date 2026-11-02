package com.stash.core.media.streaming

import com.stash.core.data.prefs.DataSaver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long a URL with [ttlMs] left may still be handed out: its lifetime minus a
 * margin of a tenth of it, at most [MAX_EXPIRY_MARGIN_MS].
 *
 * The URL is opened now but re-opened at every seek outside the buffer and every
 * dropped connection for the rest of the song. Served up to its exact expiry, one
 * that ran out seconds after the first open 403'd on the first reconnect, and a
 * placeholder item has no refresh layer: the error went to the cascade guard as an
 * audible retry. A URL this close to its end is re-resolved instead.
 */
internal fun usableLifetimeMs(ttlMs: Long): Long = ttlMs - minOf(MAX_EXPIRY_MARGIN_MS, ttlMs / 10)

/** Longest margin [usableLifetimeMs] keeps: about the length of a song. */
private const val MAX_EXPIRY_MARGIN_MS = 5 * 60_000L

/**
 * A lossless link: FLAC or ALAC from a relay (a Tidal hi-res manifest included), or the user's own
 * cloud file. What Data saver keeps off a metered network ([StreamUrlCache.get]). Tidal's AAC,
 * JioSaavn, YouTube, SoundCloud and a Qobuz MP3 are lossy.
 */
internal fun StreamUrl.isLosslessLink(): Boolean =
    codec.equals("flac", ignoreCase = true) || codec.equals("alac", ignoreCase = true) ||
        (bitsPerSample ?: 0) > 0 || origin == CloudCopyFinder.ORIGIN

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
 * 3. **No lossless link under Data saver.** [get] won't hand out a lossless
 *    link ([isLosslessLink]) while Data saver is in effect (the switch is on
 *    and the network is metered, [DataSaver.appliesNow]); it drops it and the
 *    caller resolves again, under the Data saver chain. Every reader comes
 *    through here (a tap, the look-ahead, the placeholder's resolve at play,
 *    the DASH routing), so a FLAC link saved on Wi-Fi, or by a resolve that
 *    started on Wi-Fi and finished after the phone moved to mobile data,
 *    never plays on mobile data. [StreamQualityPolicy] also clears the whole
 *    cache the moment the network or the switch changes the decision.
 *
 * The [nowMsProvider] / [elapsedMsProvider] seams exist so tests can drive
 * both clocks deterministically without `Thread.sleep`. They're
 * `internal var` so test code can swap them without going through the
 * constructor. Tests build it with no Data saver through the internal
 * no-argument constructor.
 */
@Singleton
class StreamUrlCache @Inject constructor(
    private val dataSaver: DataSaver,
) {
    /** No Data saver: for tests and hand-built graphs. */
    internal constructor() : this(DataSaver.Off)

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

    /**
     * The entry, until its usable lifetime ([usableLifetimeMs]) runs out. Stale entries are evicted.
     */
    fun get(trackId: Long): StreamUrl? {
        val url = synchronized(lock) {
            val entry = cache[trackId] ?: return null
            if (elapsedMsProvider() < entry.expiryElapsedMs) {
                entry.url
            } else {
                cache.remove(trackId)
                return null
            }
        }
        // Outside the lock: appliesNow asks the system for the network. Only a lossless entry
        // pays for that question, and it is dropped only if a newer put hasn't replaced it.
        if (url.isLosslessLink() && dataSaver.appliesNow()) {
            synchronized(lock) { if (cache[trackId]?.url === url) cache.remove(trackId) }
            return null
        }
        return url
    }

    fun put(trackId: Long, url: StreamUrl) {
        // Convert the server's absolute expiry into a remaining-lifetime
        // duration using the wall clock this once, then anchor it to the
        // monotonic clock. A non-positive TTL means the URL is already
        // expired by the wall clock at insert — don't cache a dead entry.
        val ttlMs = usableLifetimeMs(url.expiresAtMs - nowMsProvider())
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
     * tier changed. [StreamQualityPolicy], the one caller, clears it when its decision changes.
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
