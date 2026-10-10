package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.ListenSubmissionEntity
import com.stash.core.data.db.entity.ListeningEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Per-destination scrobble queue, replacing the one-boolean-column-per-destination
 * pattern (`scrobbled`, `yt_scrobbled`) that would have needed a third column and
 * a third query triplet for ListenBrainz.
 *
 * Pending work is derived, not enrolled: an event is outstanding for a target when
 * it has no [ListenSubmissionEntity.SENT] row for that target. That keeps the same
 * semantics as the boolean columns while making a new destination a string rather
 * than a migration.
 *
 * Every query is bounded by `sinceMs` so a freshly-connected destination starts
 * from the moment it was connected instead of inheriting the user's whole history
 * as a submission flood.
 */
@Dao
interface ListenSubmissionDao {

    /**
     * Listens still owed to [target], oldest first.
     *
     * Includes events never attempted (no row) and previously FAILED events under
     * the retry cap; excludes anything SENT. `started_at >= sinceMs` is what stops
     * a new destination importing history it was never asked to import.
     */
    @Query(
        """
        SELECT e.* FROM listening_events e
        LEFT JOIN listen_submissions s
            ON s.event_id = e.id AND s.target = :target
        WHERE e.started_at >= :sinceMs
          AND e.origin_device IS NULL
          AND (s.state IS NULL OR (s.state = 'FAILED' AND s.attempts < :maxAttempts))
        ORDER BY e.started_at ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingFor(
        target: String,
        sinceMs: Long,
        limit: Int = 100,
        maxAttempts: Int = ListenSubmissionEntity.MAX_ATTEMPTS,
    ): List<ListeningEventEntity>

    /**
     * Reactive count of outstanding listens for [target], so a sink can drain on
     * change the way the existing scrobblers do, and Settings can show a backlog.
     */
    @Query(
        """
        SELECT COUNT(*) FROM listening_events e
        LEFT JOIN listen_submissions s
            ON s.event_id = e.id AND s.target = :target
        WHERE e.started_at >= :sinceMs
          AND e.origin_device IS NULL
          AND (s.state IS NULL OR (s.state = 'FAILED' AND s.attempts < :maxAttempts))
        """,
    )
    fun pendingCountFor(
        target: String,
        sinceMs: Long,
        maxAttempts: Int = ListenSubmissionEntity.MAX_ATTEMPTS,
    ): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ListenSubmissionEntity)

    /** Records success. REPLACE so a prior FAILED row is cleared, not accumulated. */
    suspend fun markSent(eventId: Long, target: String, nowMs: Long) {
        upsert(
            ListenSubmissionEntity(
                eventId = eventId,
                target = target,
                state = ListenSubmissionEntity.SENT,
                attempts = 0,
                updatedAt = nowMs,
            ),
        )
    }

    /**
     * Records a failure and bumps the attempt count. Read-then-write rather than
     * an UPSERT expression because the row may not exist yet, and callers already
     * run inside the drain loop's IO context.
     */
    suspend fun markFailed(eventId: Long, target: String, nowMs: Long) {
        val attempts = (attemptsFor(eventId, target) ?: 0) + 1
        upsert(
            ListenSubmissionEntity(
                eventId = eventId,
                target = target,
                state = ListenSubmissionEntity.FAILED,
                attempts = attempts,
                updatedAt = nowMs,
            ),
        )
    }

    @Query("SELECT attempts FROM listen_submissions WHERE event_id = :eventId AND target = :target")
    suspend fun attemptsFor(eventId: Long, target: String): Int?

    @Query("SELECT * FROM listen_submissions WHERE event_id = :eventId AND target = :target")
    suspend fun rowFor(eventId: Long, target: String): ListenSubmissionEntity?

    /** Diagnostics: how many listens this destination has given up on. */
    @Query(
        """
        SELECT COUNT(*) FROM listen_submissions
        WHERE target = :target AND state = 'FAILED' AND attempts >= :maxAttempts
        """,
    )
    suspend fun exhaustedCount(
        target: String,
        maxAttempts: Int = ListenSubmissionEntity.MAX_ATTEMPTS,
    ): Int

    /** Forget a destination's state entirely — used when the user disconnects it. */
    @Query("DELETE FROM listen_submissions WHERE target = :target")
    suspend fun clearTarget(target: String): Int
}
