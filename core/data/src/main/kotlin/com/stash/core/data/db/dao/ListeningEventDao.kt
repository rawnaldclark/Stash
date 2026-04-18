package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.ListeningEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the [ListeningEventEntity] table. Kept small: the features that
 * consume listening history (Last.fm scrobbler, Stash Mixes generator)
 * do their own joins against `tracks` in their own queries.
 */
@Dao
interface ListeningEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ListeningEventEntity): Long

    @Query(
        """
        UPDATE listening_events SET scrobbled = 1
        WHERE id = :eventId
        """
    )
    suspend fun markScrobbled(eventId: Long)

    /**
     * Unscrobbled events awaiting Last.fm submission, oldest first so the
     * scrobbler submits in chronological order. Last.fm accepts a batch of
     * up to 50 per request; callers chunk accordingly.
     */
    @Query(
        """
        SELECT * FROM listening_events
        WHERE scrobbled = 0
        ORDER BY started_at ASC
        LIMIT :limit
        """
    )
    suspend fun pendingScrobbles(limit: Int = 100): List<ListeningEventEntity>

    /** Count of unscrobbled events. Useful for Settings UI ("12 pending"). */
    @Query("SELECT COUNT(*) FROM listening_events WHERE scrobbled = 0")
    fun pendingScrobbleCount(): Flow<Int>

    /**
     * Per-track play counts in a recency window, used by the (future)
     * Stash Mixes engine. Window is expressed as a cutoff epoch millis;
     * callers pass `now - 30 days` or similar.
     */
    @Query(
        """
        SELECT track_id AS trackId, COUNT(*) AS plays
        FROM listening_events
        WHERE started_at >= :sinceEpochMs
        GROUP BY track_id
        ORDER BY plays DESC
        LIMIT :limit
        """
    )
    suspend fun topTracksSince(sinceEpochMs: Long, limit: Int = 50): List<TrackPlayCount>

    data class TrackPlayCount(
        val trackId: Long,
        val plays: Int,
    )
}
