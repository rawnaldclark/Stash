package com.stash.core.data.cache

import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.MergedDiscography
import com.stash.core.data.discography.NoopDiscographySupplement
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-facing result of an [ArtistCache.get] lookup.
 *
 * [Fresh] means the entry is within the 6-hour TTL; [Stale] means the
 * entry exists but was fetched more than TTL ago.
 *
 * [Stale.refreshFailed] is the spec Phase-3 advisory flag: when the
 * in-flight refresh fails (network down, InnerTube 5xx, etc.) we emit
 * a second [Stale] with this flag `true` so the ViewModel can surface a
 * Snackbar WITHOUT swapping the screen into an error state. The cached
 * data keeps rendering; only the badge/Snackbar changes.
 */
sealed interface CachedProfile {
    val profile: ArtistProfile

    data class Fresh(override val profile: ArtistProfile) : CachedProfile

    data class Stale(
        override val profile: ArtistProfile,
        val refreshFailed: Boolean = false,
    ) : CachedProfile
}

/**
 * Two-tier stale-while-revalidate cache for [ArtistProfile].
 *
 * Tiers:
 * - **Memory** — 20-entry access-order LRU keyed by `artistId`. Zero-latency
 *   re-entries (< 1 ms in practice) — this is what lets a back-press-then-
 *   re-tap-the-same-artist feel instant.
 * - **Disk**   — [ArtistProfileCacheEntity] in Room, capped to 20 rows via
 *   `ArtistProfileCacheDao.evictOldest`. Survives process death.
 *
 * Staleness: entries older than [TTL_MS] are served as [CachedProfile.Stale]
 * while a background refresh runs; on success a [CachedProfile.Fresh] is
 * emitted. On refresh failure a second [CachedProfile.Stale] is emitted
 * with `refreshFailed = true` — the cached data stays visible so the user
 * never sees an empty error screen for a working feature.
 *
 * Thread safety: the [LinkedHashMap] is guarded by `synchronized(memory)`
 * for the tiny reads/writes we do; the [ArtistProfileCacheDao] calls are
 * suspendable so they never block the main thread.
 *
 * The [now] constructor parameter is a seam for testing — production uses
 * `System::currentTimeMillis`; tests inject a fixed clock so they don't
 * have to sleep through the TTL.
 */
@Singleton
class ArtistCache(
    private val dao: ArtistProfileCacheDao,
    private val api: YTMusicApiClient,
    private val now: () -> Long = System::currentTimeMillis,
    // Defaulted to the no-op so the existing test constructions (which pass
    // only dao/api/now) keep compiling; production injects the real Qobuz
    // supplement via the @Inject secondary constructor below.
    private val supplement: DiscographySupplement = NoopDiscographySupplement(),
    private val aboutEnricher: ArtistAboutEnricher = NoopArtistAboutEnricher(),
) {

    /**
     * Hilt-visible entry point. Delegates to the primary constructor with
     * the `System::currentTimeMillis` clock default.
     *
     * The primary constructor stays non-`@Inject` so tests can pass a fixed
     * clock (`now = { 1_000L }`) without Hilt trying — and failing — to
     * resolve a `Function0<Long>` binding for the default.
     */
    @Inject
    constructor(
        dao: ArtistProfileCacheDao,
        api: YTMusicApiClient,
        supplement: DiscographySupplement,
        aboutEnricher: ArtistAboutEnricher,
    ) : this(dao, api, System::currentTimeMillis, supplement, aboutEnricher)

    /**
     * Lenient JSON codec — matches the one in [ArtistCacheEntityFixtures]
     * so test fixtures and production reads round-trip identically. Future
     * DTO additions won't invalidate cached rows thanks to
     * `ignoreUnknownKeys = true`.
     */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 20-entry access-order LRU. `removeEldestEntry` is the JDK-supplied
     * hook that evicts the least-recently-accessed row once [size] exceeds
     * the bound — same behaviour `LruCache` gives us, without pulling in
     * an Android dependency for what is a pure-Kotlin cache.
     */
    private val memory = object : LinkedHashMap<String, ArtistProfileCacheEntity>(
        16, 0.75f, /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ArtistProfileCacheEntity>?,
        ): Boolean = size > MEMORY_LRU_MAX
    }

    /** Testing hook: is [id] currently resident in the memory LRU? */
    fun memoryContains(id: String): Boolean =
        synchronized(memory) { memory.containsKey(id) }

    /**
     * Load an [ArtistProfile] via the SWR pipeline.
     *
     * Emission semantics:
     *  - **Miss**         -> exactly one `Fresh`.
     *  - **Hit (fresh)**  -> exactly one `Fresh`, no network call.
     *  - **Hit (stale)**  -> one `Stale` immediately, then `Fresh` after
     *                        a successful refresh, or another `Stale` with
     *                        `refreshFailed = true` on refresh failure.
     *
     * Miss failures propagate (no cached copy to fall back to); callers
     * handle them via Flow's normal error channel.
     */
    fun get(artistId: String): Flow<CachedProfile> = flow {
        val hit = synchronized(memory) { memory[artistId] } ?: dao.get(artistId)
        if (hit != null) {
            // Warm the memory tier (promotes to MRU in access order).
            synchronized(memory) { memory[artistId] = hit }
            val cachedProfile = json.decodeFromString<ArtistProfile>(hit.json)
            val age = now() - hit.fetchedAt
            if (age < TTL_MS) {
                emit(CachedProfile.Fresh(cachedProfile))
                return@flow
            }
            // Past TTL — surface stale data first, then try to refresh.
            emit(CachedProfile.Stale(cachedProfile))
            try {
                val refreshed = fetchAndMerge(artistId)
                persist(refreshed)
                emit(CachedProfile.Fresh(refreshed))
            } catch (t: Throwable) {
                // Never swallow cancellation — doing so would mask rapid-
                // navigation teardown into a spurious Stale(refreshFailed=true)
                // emission on a detached collector, breaking structured concurrency.
                if (t is CancellationException) throw t
                // Don't blow up the screen — the user already has usable data.
                // Surface the refresh failure for the ViewModel's Snackbar.
                emit(CachedProfile.Stale(cachedProfile, refreshFailed = true))
            }
            return@flow
        }

        // Cold miss: no memory or disk tier hit — network is the source of truth.
        val profile = fetchAndMerge(artistId)
        persist(profile)
        emit(CachedProfile.Fresh(profile))
    }

    /**
     * Fetch the YT artist profile and merge in the Qobuz discography supplement.
     *
     * `api.getArtist` is deliberately OUTSIDE the try: a real YT/network failure
     * must still propagate to the caller's existing handling (cold-miss error
     * channel, or the stale-refresh `refreshFailed = true` path). Only the
     * SUPPLEMENT is best-effort — any timeout or failure degrades to the YT-only
     * lists, never escaping. Bounded by [SUPPLEMENT_TIMEOUT_MS] because the qbdlx
     * token pool can hang and must not stall the artist page.
     */
    private suspend fun fetchAndMerge(artistId: String): ArtistProfile = coroutineScope {
        val yt = api.getArtist(artistId)   // REQUIRED — failure propagates
        val discographyDeferred = async {
            try {
                withTimeout(SUPPLEMENT_TIMEOUT_MS) { supplement.mergeInto(yt.name, yt.albums, yt.singles) }
            } catch (e: TimeoutCancellationException) { MergedDiscography(yt.albums, yt.singles) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { MergedDiscography(yt.albums, yt.singles) }
        }
        val aboutDeferred = async {
            try {
                withTimeout(ABOUT_TIMEOUT_MS) { aboutEnricher.enrich(yt.name) }
            } catch (e: TimeoutCancellationException) { null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
        }
        val merged = discographyDeferred.await()
        val about = aboutDeferred.await()
        yt.copy(albums = merged.albums, singles = merged.singles, about = about)
    }

    /**
     * Write [profile] to both tiers with a fresh `fetchedAt`, then trim
     * the disk tier to the same cap as the memory LRU. Memory is updated
     * last so a DAO failure doesn't leave memory ahead of disk.
     */
    private suspend fun persist(profile: ArtistProfile) {
        val entity = ArtistProfileCacheEntity(
            artistId = profile.id,
            json = json.encodeToString(profile),
            fetchedAt = now(),
        )
        dao.upsert(entity)
        dao.evictOldest(keep = MEMORY_LRU_MAX)
        synchronized(memory) { memory[profile.id] = entity }
    }

    companion object {
        /** 6 hours in millis — spec §3.4 TTL. */
        private const val TTL_MS: Long = 6 * 60 * 60 * 1000L

        /** Shared bound for the memory LRU and the disk-tier `evictOldest`. */
        private const val MEMORY_LRU_MAX = 20

        /** Ceiling on the Qobuz supplement merge; past this we serve YT-only. */
        private const val SUPPLEMENT_TIMEOUT_MS = 4_000L

        /** About enricher bound — kept <= SUPPLEMENT_TIMEOUT_MS so it never extends cold-miss first paint. */
        private const val ABOUT_TIMEOUT_MS: Long = 4_000L
    }
}
