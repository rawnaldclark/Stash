package com.stash.core.data.cache

import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.entity.ArtistProfileCacheEntity

/**
 * Pure-JVM fake of [ArtistProfileCacheDao] used by [ArtistCacheTest].
 *
 * A `LinkedHashMap` is intentionally chosen so `evictOldest` retains
 * the newest-by-`fetchedAt` rows rather than newest-by-insertion — this
 * mirrors the SQL query's `ORDER BY fetched_at DESC LIMIT :keep`.
 */
internal class InMemoryDao : ArtistProfileCacheDao {

    private val store: LinkedHashMap<String, ArtistProfileCacheEntity> = LinkedHashMap()

    override suspend fun upsert(entity: ArtistProfileCacheEntity) {
        store[entity.artistId] = entity
    }

    override suspend fun get(artistId: String): ArtistProfileCacheEntity? = store[artistId]

    override suspend fun evictOldest(keep: Int) {
        val newest = store.values.sortedByDescending { it.fetchedAt }.take(keep)
        val keepIds = newest.mapTo(HashSet()) { it.artistId }
        val toRemove = store.keys.filter { it !in keepIds }
        toRemove.forEach { store.remove(it) }
    }

    override suspend fun clearAll() {
        store.clear()
    }
}
