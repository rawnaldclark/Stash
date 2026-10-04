package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.FlacUpgradeQueueEntity
import com.stash.core.model.FlacUpgradeStatus
import java.time.Instant

/** Persisted worklist for the batch FLAC-upgrade worker (spec 2026-07-22 §3). */
@Dao
interface FlacUpgradeQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FlacUpgradeQueueEntity>)

    @Query("DELETE FROM flac_upgrade_queue")
    suspend fun clearAll()

    /**
     * New batch = replace the old one wholesale (single-batch semantics). The worker
     * drains rows in [trackIds] order: each row is stamped one millisecond after the
     * last, so [pendingTrackIds]' `enqueued_at` sort can't fall back to track-id order
     * on a tie (the sweep puts never-tried tracks first).
     */
    @Transaction
    suspend fun startBatch(trackIds: List<Long>) {
        clearAll()
        val start = Instant.now()
        insertAll(
            trackIds.mapIndexed { i, id ->
                FlacUpgradeQueueEntity(trackId = id, enqueuedAt = start.plusMillis(i.toLong()))
            },
        )
    }

    @Query("SELECT track_id FROM flac_upgrade_queue WHERE status = 'PENDING' ORDER BY enqueued_at ASC")
    suspend fun pendingTrackIds(): List<Long>

    @Query("UPDATE flac_upgrade_queue SET status = :status WHERE track_id = :trackId")
    suspend fun setStatus(trackId: Long, status: FlacUpgradeStatus)

    @Query("SELECT COUNT(*) FROM flac_upgrade_queue WHERE status = :status")
    suspend fun countByStatus(status: FlacUpgradeStatus): Int

    @Query("SELECT COUNT(*) FROM flac_upgrade_queue")
    suspend fun countAll(): Int

    /** Cancel: drop the not-yet-processed remainder, keep terminal rows. */
    @Query("DELETE FROM flac_upgrade_queue WHERE status = 'PENDING'")
    suspend fun clearPending()
}
