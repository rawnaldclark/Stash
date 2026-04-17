package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.ArtistProfileCacheEntity

/**
 * DAO for the SWR artist-profile cache (disk tier).
 *
 * Kept intentionally small — writes are single-row upserts, reads are a
 * point-lookup by primary key, and the only housekeeping query is the
 * LIMIT-based [evictOldest] that pairs with the 20-entry memory LRU in
 * [com.stash.core.data.cache.ArtistCache].
 */
@Dao
interface ArtistProfileCacheDao {

    /**
     * Insert-or-replace a cache row. Callers always pass a fresh
     * `fetched_at` when overwriting so TTL comparisons stay meaningful.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtistProfileCacheEntity)

    /** Point lookup by primary key; null when no row exists for [artistId]. */
    @Query("SELECT * FROM artist_profile_cache WHERE artist_id = :artistId")
    suspend fun get(artistId: String): ArtistProfileCacheEntity?

    /**
     * Delete all rows except the [keep] newest by `fetched_at`.
     *
     * Run after every successful write to cap on-disk footprint at the same
     * bound as the memory LRU (20). The subquery uses `LIMIT :keep`; callers
     * pass the desired cap explicitly rather than hard-coding it so the test
     * can drive it with different values.
     */
    @Query(
        """
        DELETE FROM artist_profile_cache
        WHERE artist_id NOT IN (
            SELECT artist_id FROM artist_profile_cache
            ORDER BY fetched_at DESC LIMIT :keep
        )
        """
    )
    suspend fun evictOldest(keep: Int)
}
