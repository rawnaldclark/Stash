package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import kotlinx.coroutines.flow.Flow

/** Queue DAO for Stash Mix discovery candidates. */
@Dao
interface DiscoveryQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(entry: DiscoveryQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfNew(entries: List<DiscoveryQueueEntity>)

    /** Pending entries for the discovery worker to drain. */
    @Query(
        """
        SELECT * FROM discovery_queue
        WHERE status = 'PENDING'
        ORDER BY queued_at ASC
        LIMIT :limit
        """
    )
    suspend fun getPending(limit: Int): List<DiscoveryQueueEntity>

    /**
     * Count of discovery entries completed within [sinceMillis] for a
     * specific recipe — enforces the per-recipe weekly cap so discovery
     * doesn't spiral.
     */
    @Query(
        """
        SELECT COUNT(*) FROM discovery_queue
        WHERE recipe_id = :recipeId
          AND status = 'DONE'
          AND completed_at >= :sinceMillis
        """
    )
    suspend fun countRecentCompletedForRecipe(recipeId: Long, sinceMillis: Long): Int

    @Query(
        """
        UPDATE discovery_queue
        SET status = :status, track_id = :trackId,
            completed_at = :completedAt, error_message = :errorMessage
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        trackId: Long? = null,
        completedAt: Long? = null,
        errorMessage: String? = null,
    )

    /** Diagnostics projection for the Settings/debug view. */
    @Query(
        """
        SELECT status, COUNT(*) AS n FROM discovery_queue GROUP BY status
        """
    )
    fun observeStatusCounts(): Flow<List<StatusCount>>

    data class StatusCount(val status: String, val n: Int)

    /**
     * Does this recipe already have a pending/complete entry for
     * (artist, title)? Prevents duplicate discoveries across refreshes.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM discovery_queue
            WHERE recipe_id = :recipeId
              AND LOWER(artist) = LOWER(:artist)
              AND LOWER(title) = LOWER(:title)
        )
        """
    )
    suspend fun existsForRecipe(recipeId: Long, artist: String, title: String): Boolean
}
