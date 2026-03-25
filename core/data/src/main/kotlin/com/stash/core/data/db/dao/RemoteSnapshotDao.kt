package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity

/**
 * Data-access object for remote playlist and track snapshot entities.
 *
 * Snapshots are written during the "fetch" phase of a sync run and read
 * during the "diff" phase. Old data is pruned after a retention window.
 */
@Dao
interface RemoteSnapshotDao {

    // ── Playlist snapshot operations ────────────────────────────────────

    /** Insert a single playlist snapshot. Returns the generated row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSnapshot(snapshot: RemotePlaylistSnapshotEntity): Long

    /** Insert multiple playlist snapshots in a batch. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSnapshots(snapshots: List<RemotePlaylistSnapshotEntity>): List<Long>

    /** Retrieve all playlist snapshots belonging to a given sync run. */
    @Query("SELECT * FROM remote_playlist_snapshots WHERE sync_id = :syncId ORDER BY playlist_name ASC")
    suspend fun getPlaylistSnapshotsBySyncId(syncId: Long): List<RemotePlaylistSnapshotEntity>

    /** Retrieve a single playlist snapshot by its primary key. */
    @Query("SELECT * FROM remote_playlist_snapshots WHERE id = :id LIMIT 1")
    suspend fun getPlaylistSnapshotById(id: Long): RemotePlaylistSnapshotEntity?

    // ── Track snapshot operations ───────────────────────────────────────

    /** Insert a single track snapshot. Returns the generated row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackSnapshot(snapshot: RemoteTrackSnapshotEntity): Long

    /** Insert multiple track snapshots in a batch. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackSnapshots(snapshots: List<RemoteTrackSnapshotEntity>): List<Long>

    /** Retrieve all track snapshots for a given playlist snapshot, ordered by position. */
    @Query("SELECT * FROM remote_track_snapshots WHERE snapshot_playlist_id = :snapshotPlaylistId ORDER BY position ASC")
    suspend fun getTrackSnapshotsByPlaylistId(snapshotPlaylistId: Long): List<RemoteTrackSnapshotEntity>

    /** Retrieve all track snapshots belonging to a given sync run. */
    @Query("SELECT * FROM remote_track_snapshots WHERE sync_id = :syncId ORDER BY snapshot_playlist_id, position ASC")
    suspend fun getTrackSnapshotsBySyncId(syncId: Long): List<RemoteTrackSnapshotEntity>

    // ── Cleanup operations ──────────────────────────────────────────────

    /** Delete all playlist snapshots for a given sync run. */
    @Query("DELETE FROM remote_playlist_snapshots WHERE sync_id = :syncId")
    suspend fun deletePlaylistSnapshotsBySyncId(syncId: Long)

    /** Delete all track snapshots for a given sync run. */
    @Query("DELETE FROM remote_track_snapshots WHERE sync_id = :syncId")
    suspend fun deleteTrackSnapshotsBySyncId(syncId: Long)

    /**
     * Delete all snapshots (playlist + track) for a given sync run
     * within a single transaction.
     */
    @Transaction
    suspend fun deleteAllSnapshotsBySyncId(syncId: Long) {
        deleteTrackSnapshotsBySyncId(syncId)
        deletePlaylistSnapshotsBySyncId(syncId)
    }

    /**
     * Prune snapshot data older than the given epoch-millis timestamp.
     *
     * Removes both playlist and track snapshot rows whose [fetchedAt]
     * (via their parent playlist snapshot) predates the cutoff.
     */
    @Transaction
    suspend fun pruneOlderThan(cutoffEpochMillis: Long) {
        pruneTrackSnapshotsOlderThan(cutoffEpochMillis)
        prunePlaylistSnapshotsOlderThan(cutoffEpochMillis)
    }

    /** Internal: remove track snapshots whose parent playlist snapshot is stale. */
    @Query(
        """
        DELETE FROM remote_track_snapshots
        WHERE snapshot_playlist_id IN (
            SELECT id FROM remote_playlist_snapshots WHERE fetched_at < :cutoffEpochMillis
        )
        """
    )
    suspend fun pruneTrackSnapshotsOlderThan(cutoffEpochMillis: Long)

    /** Internal: remove stale playlist snapshots. */
    @Query("DELETE FROM remote_playlist_snapshots WHERE fetched_at < :cutoffEpochMillis")
    suspend fun prunePlaylistSnapshotsOlderThan(cutoffEpochMillis: Long)
}
