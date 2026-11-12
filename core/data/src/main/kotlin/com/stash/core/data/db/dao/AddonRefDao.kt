package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.stash.core.data.db.entity.AddonRefEntity

/** Storage for [AddonRefEntity]. Freshness (TTLs) is the caller's concern. */
@Dao
interface AddonRefDao {

    @Query("SELECT * FROM addon_refs WHERE addon_id = :addonId AND song_key = :songKey")
    suspend fun get(addonId: String, songKey: String): AddonRefEntity?

    @Upsert
    suspend fun upsert(entity: AddonRefEntity)

    /** Drops [addonId]'s rows whose newest stamp is older than [before]; keeps the table small. */
    @Query(
        "DELETE FROM addon_refs WHERE addon_id = :addonId AND resolved_at < :before " +
            "AND COALESCE(lossless_miss_at, 0) < :before AND COALESCE(aac_miss_at, 0) < :before",
    )
    suspend fun deleteOlderThan(addonId: String, before: Long): Int
}
