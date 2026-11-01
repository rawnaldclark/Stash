package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.DataSaver
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
    fun get_stopsServingAUrlTheLastMinutesBeforeItExpires() {
        // A URL opened seconds before it dies 403s on the first reconnect, and a
        // placeholder has no refresh layer. One hour left: the last 5 minutes go.
        cache.put(1L, url("https://cdn/file", expiresAtMs = 3_600_000L))

        mono = 3_600_000L - 6 * 60_000L
        assertThat(cache.get(1L)).isNotNull()
        mono = 3_600_000L - 4 * 60_000L
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

    /**
     * Data saver: no lossless link off the cache while it applies, whoever saved it. A FLAC link
     * saved on Wi-Fi is dropped (not hidden) on mobile data; a lossy one is served as before.
     */
    @Test
    fun get_servesNoLosslessLinkWhileDataSaverApplies() {
        var metered = false
        val guarded = StreamUrlCache(DataSaver(switchOn = { true }, metered = { metered })).apply {
            nowMsProvider = { wall }
            elapsedMsProvider = { mono }
        }
        val flac = StreamUrl("https://cdn/a.flac?etsp=1", expiresAtMs = 1_000_000L, codec = "flac", origin = "qbdlx")
        val aac = StreamUrl("https://cdn/b.m4a", expiresAtMs = 1_000_000L, codec = "aac", origin = "tidal")
        guarded.put(1L, flac)
        guarded.put(2L, aac)

        assertThat(guarded.get(1L)).isEqualTo(flac) // Wi-Fi
        metered = true // mobile data
        assertThat(guarded.get(1L)).isNull()
        assertThat(guarded.get(2L)).isEqualTo(aac)
        metered = false // back on Wi-Fi: it was dropped, so it resolves again
        assertThat(guarded.get(1L)).isNull()
    }

    @Test
    fun get_servesLosslessLinksWhenDataSaverIsOff() {
        val unguarded = StreamUrlCache(DataSaver(switchOn = { false }, metered = { true })).apply {
            nowMsProvider = { wall }
            elapsedMsProvider = { mono }
        }
        val flac = StreamUrl("https://cdn/a.flac?etsp=1", expiresAtMs = 1_000_000L, codec = "flac")
        unguarded.put(1L, flac)

        assertThat(unguarded.get(1L)).isEqualTo(flac)
    }

    @Test
    fun isLosslessLink_isFlacAlacHiResOrACloudCopy() {
        fun link(codec: String?, bits: Int? = null, origin: String? = null) =
            StreamUrl("https://cdn/x", expiresAtMs = 1L, codec = codec, bitsPerSample = bits, origin = origin)

        assertThat(link("flac").isLosslessLink()).isTrue()
        assertThat(link("FLAC").isLosslessLink()).isTrue()
        assertThat(link("alac").isLosslessLink()).isTrue()
        assertThat(link(null, bits = 24, origin = "tidal").isLosslessLink()).isTrue()
        assertThat(link(null, origin = CloudCopyFinder.ORIGIN).isLosslessLink()).isTrue()

        assertThat(link("aac", origin = "tidal").isLosslessLink()).isFalse()
        assertThat(link("mp3", origin = "squid").isLosslessLink()).isFalse()
        assertThat(link("opus", origin = "youtube").isLosslessLink()).isFalse()
        assertThat(link("aac", origin = "jiosaavn").isLosslessLink()).isFalse()
        assertThat(link(null, origin = "soundcloud").isLosslessLink()).isFalse()
    }
}
