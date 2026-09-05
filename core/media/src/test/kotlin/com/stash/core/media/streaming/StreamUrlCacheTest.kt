package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamUrlCacheTest {

    // Two independent fake clocks: `wall` is what System.currentTimeMillis()
    // would return (drives the TTL derivation at insert); `mono` is the
    // monotonic clock the cache counts down against.
    private var wall = 0L
    private var mono = 0L
    private val cache = StreamUrlCache().apply {
        nowMsProvider = { wall }
        elapsedMsProvider = { mono }
    }

    private fun url(s: String, expiresAtMs: Long) =
        StreamUrl(url = s, expiresAtMs = expiresAtMs)

    @Test
    fun get_returnsNullForUnknownKey() {
        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun get_returnsCachedValueWithinTtl() {
        // Inserted at wall=0 with absolute expiry 1000 → TTL 1000ms, anchored
        // to mono=0 → valid until mono 1000.
        cache.put(1L, url("https://cdn/file?etsp=1", expiresAtMs = 1000L))
        mono = 500L

        val hit = cache.get(1L)

        assertThat(hit).isNotNull()
        // The returned StreamUrl still carries the ORIGINAL absolute expiry
        // for downstream callers (prefetch freshness check).
        assertThat(hit!!.expiresAtMs).isEqualTo(1000L)
    }

    @Test
    fun get_returnsNullPastExpiry() {
        cache.put(1L, url("https://cdn/file?etsp=1", expiresAtMs = 1000L))
        mono = 1001L

        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun get_returnsNullAtExactExpiryBoundary() {
        // Valid while mono < expiry; at mono == expiry it's no longer served.
        cache.put(1L, url("https://cdn/file?etsp=1", expiresAtMs = 1000L))
        mono = 1000L

        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun put_dropsAlreadyExpiredEntry() {
        // Absolute expiry already in the past at insert (wall has advanced
        // past it) → non-positive TTL → not cached at all.
        wall = 2000L
        cache.put(1L, url("https://cdn/stale", expiresAtMs = 1000L))

        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun expiry_isImmuneToWallClockJumpAfterInsert() {
        // THE robustness property this redesign adds: insert at wall=1000
        // with absolute expiry 2000 → TTL 1000ms from mono=0 → valid until
        // mono 1000.
        wall = 1000L
        mono = 0L
        cache.put(1L, url("https://cdn/file", expiresAtMs = 2000L))

        // Wall clock jumps BACKWARD an hour (NTP / manual change). A
        // wall-clock cache would still think the entry is valid for ages
        // (expiresAtMs 2000 >> new wall). The monotonic clock has only
        // advanced 500ms, so the entry is correctly still valid...
        wall = -3_600_000L
        mono = 500L
        assertThat(cache.get(1L)).isNotNull()

        // ...and still expires on schedule by real elapsed time, regardless
        // of where the wall clock is.
        mono = 1000L
        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun put_overwritesExistingEntry() {
        cache.put(1L, url("https://cdn/old", expiresAtMs = 1000L))
        cache.put(1L, url("https://cdn/new", expiresAtMs = 2000L))
        mono = 1500L

        val hit = cache.get(1L)

        assertThat(hit).isNotNull()
        assertThat(hit!!.url).isEqualTo("https://cdn/new")
        assertThat(hit.expiresAtMs).isEqualTo(2000L)
    }

    @Test
    fun invalidate_dropsEntry() {
        cache.put(1L, url("https://cdn/file", expiresAtMs = 1000L))
        mono = 500L

        cache.invalidate(1L)

        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun lru_evictsLeastRecentlyUsedBeyondCapacity() {
        // Long-session memory bound: insert well past the cap, all with a
        // long TTL so nothing expires, and confirm the map doesn't grow
        // without limit — the oldest untouched keys are evicted.
        val farFuture = 10_000_000L
        for (id in 1L..600L) {
            cache.put(id, url("https://cdn/$id", expiresAtMs = farFuture))
        }
        // 600 inserted, cap is 512 → the earliest 88 (ids 1..88) are gone,
        // the most recent 512 (ids 89..600) survive.
        assertThat(cache.get(1L)).isNull()
        assertThat(cache.get(88L)).isNull()
        assertThat(cache.get(89L)).isNotNull()
        assertThat(cache.get(600L)).isNotNull()
    }

    @Test
    fun lru_recencyIsRefreshedOnRead() {
        val farFuture = 10_000_000L
        // Fill exactly to capacity (ids 1..512).
        for (id in 1L..512L) {
            cache.put(id, url("https://cdn/$id", expiresAtMs = farFuture))
        }
        // Touch id=1 so it becomes most-recently-used.
        assertThat(cache.get(1L)).isNotNull()
        // Insert one more → eldest is now id=2 (1 was just used), not id=1.
        cache.put(513L, url("https://cdn/513", expiresAtMs = farFuture))

        assertThat(cache.get(1L)).isNotNull()   // survived (recently read)
        assertThat(cache.get(2L)).isNull()       // evicted as the true eldest
    }

    /** A URL is only right for the tier it was minted under; when that changes, every entry goes. */
    @Test
    fun clear_dropsEveryEntry() {
        cache.put(1L, url("https://cdn/1", expiresAtMs = 10_000L))
        cache.put(2L, url("https://cdn/2", expiresAtMs = 10_000L))

        cache.clear()

        assertThat(cache.get(1L)).isNull()
        assertThat(cache.get(2L)).isNull()
    }
}
