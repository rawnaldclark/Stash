package com.stash.core.data.cache

import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
class ArtistCache @Inject constructor(
    private val dao: ArtistProfileCacheDao,
    private val api: YTMusicApiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {

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
                val refreshed = api.getArtist(artistId)
                persist(refreshed)
                emit(CachedProfile.Fresh(refreshed))
            } catch (t: Throwable) {
                // Don't blow up the screen — the user already has usable data.
                // Surface the refresh failure for the ViewModel's Snackbar.
                emit(CachedProfile.Stale(cachedProfile, refreshFailed = true))
            }
            return@flow
        }

        // Cold miss: no memory or disk tier hit — network is the source of truth.
        val profile = api.getArtist(artistId)
        persist(profile)
        emit(CachedProfile.Fresh(profile))
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
    }
}
