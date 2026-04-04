package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for [DownloadQueueEntity].
 *
 * Manages the download work queue with insert, status updates,
 * retry tracking, and cleanup operations.
 */
@Dao
interface DownloadQueueDao {

    // ── Inserts ─────────────────────────────────────────────────────────

    /** Insert a single download queue entry. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DownloadQueueEntity): Long

    /** Insert multiple download queue entries. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DownloadQueueEntity>): List<Long>

    // ── Queries ─────────────────────────────────────────────────────────

    /** Reactive stream of pending downloads, ordered by creation time. */
    @Query("SELECT * FROM download_queue WHERE status = 'PENDING' ORDER BY created_at ASC")
    fun getPending(): Flow<List<DownloadQueueEntity>>

    /** Reactive stream of downloads filtered by [status]. */
    @Query("SELECT * FROM download_queue WHERE status = :status ORDER BY created_at ASC")
    fun getByStatus(status: DownloadStatus): Flow<List<DownloadQueueEntity>>

    /** Find a download queue entry by the associated track ID. */
    @Query("SELECT * FROM download_queue WHERE track_id = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: Long): DownloadQueueEntity?

    /** Retrieve all pending downloads for a specific sync run, ordered by creation time. */
    @Query("SELECT * FROM download_queue WHERE sync_id = :syncId AND status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPendingBySyncId(syncId: Long): List<DownloadQueueEntity>

    /**
     * Retrieve failed downloads that should be retried (max 3 attempts),
     * filtered to only include tracks from the given [sources].
     * Spotify tracks (needing YouTube search) are prioritized first.
     */
    @Query("""
        SELECT dq.* FROM download_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.status = 'FAILED' AND dq.retry_count < 3
          AND t.source IN (:sources)
        ORDER BY (CASE WHEN dq.youtube_url IS NULL THEN 0 ELSE 1 END) ASC, dq.created_at ASC
    """)
    suspend fun getRetryableBySources(sources: List<String>): List<DownloadQueueEntity>

    // ── Updates ─────────────────────────────────────────────────────────

    /**
     * Update the status of a download queue entry.
     *
     * @param id            Row ID of the queue entry.
     * @param status        New [DownloadStatus].
     * @param errorMessage  Error description when status is FAILED, null otherwise.
     * @param completedAt   Epoch-millis timestamp when the download finished, or null.
     */
    @Query(
        """
        UPDATE download_queue
        SET status = :status,
            error_message = :errorMessage,
            completed_at = :completedAt
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        errorMessage: String? = null,
        completedAt: Long? = null,
    )

    /** Increment the retry count for a download queue entry. */
    @Query("UPDATE download_queue SET retry_count = retry_count + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)

    // ── Cleanup ─────────────────────────────────────────────────────────

    /** Delete all completed download entries to free up space. */
    @Query("DELETE FROM download_queue WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    /** Reset retry count for all failed entries so they can be retried after a bug fix. */
    @Query("UPDATE download_queue SET retry_count = 0, status = 'FAILED' WHERE status = 'FAILED' AND retry_count >= 3")
    suspend fun resetExhaustedRetries()

    /**
     * Find tracks that need downloading but have no active queue entry.
     * These are tracks where is_downloaded=0 AND there's no PENDING/IN_PROGRESS
     * queue entry. Returns track IDs that need fresh queue entries.
     *
     * This covers tracks that:
     * - Had all their retries exhausted and queue entries cleaned up
     * - Were added by sync but never got queue entries (edge case)
     * - Had their queue entries deleted
     */
    @Query("""
        SELECT t.id FROM tracks t
        WHERE t.is_downloaded = 0
          AND t.source IN (:sources)
          AND t.id NOT IN (
              SELECT dq.track_id FROM download_queue dq
              WHERE dq.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
          )
    """)
    suspend fun getUnqueuedTrackIds(sources: List<String>): List<Long>
}
