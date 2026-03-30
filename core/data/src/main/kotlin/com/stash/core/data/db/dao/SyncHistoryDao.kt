package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.model.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for [SyncHistoryEntity].
 *
 * Provides insert, lookup, pagination, and status/count update operations
 * for sync-run records.
 */
@Dao
interface SyncHistoryDao {

    // ── Insert ──────────────────────────────────────────────────────────

    /** Insert a new sync history record. Returns the generated row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncHistoryEntity): Long

    // ── Queries ─────────────────────────────────────────────────────────

    /** The most recent sync record, or null if none exist. */
    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT 1")
    suspend fun getLatest(): SyncHistoryEntity?

    /**
     * Paginated list of all sync records, most recent first.
     *
     * @param limit   Maximum number of rows to return.
     * @param offset  Number of rows to skip (for pagination).
     */
    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int, offset: Int): List<SyncHistoryEntity>

    /** Reactive stream of all sync records, most recent first. */
    @Query("SELECT * FROM sync_history ORDER BY started_at DESC")
    fun observeAll(): Flow<List<SyncHistoryEntity>>

    /** Reactive stream of the single most recent sync record, or null. */
    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT 1")
    fun observeLatest(): Flow<SyncHistoryEntity?>

    /**
     * Reactive stream of the N most recent sync records.
     *
     * @param limit Maximum number of records to observe.
     */
    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT :limit")
    fun getRecentSyncs(limit: Int = 20): Flow<List<SyncHistoryEntity>>

    /** Find a sync record by primary key. */
    @Query("SELECT * FROM sync_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SyncHistoryEntity?

    // ── Updates ─────────────────────────────────────────────────────────

    /**
     * Update the status and completion timestamp for a sync run.
     *
     * @param id           Row ID of the sync record.
     * @param status       New [SyncState] value.
     * @param completedAt  Epoch-millis timestamp, or null if still running.
     * @param errorMessage Optional error description when status is FAILED.
     */
    @Query(
        """
        UPDATE sync_history
        SET status = :status,
            completed_at = :completedAt,
            error_message = :errorMessage
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: SyncState,
        completedAt: Long? = null,
        errorMessage: String? = null,
    )

    /**
     * Update the running tallies for a sync run.
     *
     * @param id                Row ID of the sync record.
     * @param playlistsChecked  Number of playlists inspected so far.
     * @param newTracksFound    Number of new tracks discovered.
     * @param tracksDownloaded  Number of tracks successfully downloaded.
     * @param tracksFailed      Number of tracks that failed to download.
     * @param bytesDownloaded   Total bytes downloaded so far.
     */
    @Query(
        """
        UPDATE sync_history
        SET playlists_checked = :playlistsChecked,
            new_tracks_found = :newTracksFound,
            tracks_downloaded = :tracksDownloaded,
            tracks_failed = :tracksFailed,
            bytes_downloaded = :bytesDownloaded
        WHERE id = :id
        """
    )
    suspend fun updateCounts(
        id: Long,
        playlistsChecked: Int,
        newTracksFound: Int,
        tracksDownloaded: Int,
        tracksFailed: Int,
        bytesDownloaded: Long,
    )

    /**
     * Update the diagnostics JSON for a sync run.
     *
     * @param id          Row ID of the sync record.
     * @param diagnostics JSON-serialized list of [SyncStepResult], or null.
     */
    @Query("UPDATE sync_history SET diagnostics = :diagnostics WHERE id = :id")
    suspend fun updateDiagnostics(id: Long, diagnostics: String?)

    /**
     * Update the final result of a sync run in a single statement,
     * setting status, completion timestamp, error message, and all tallies.
     */
    @Query(
        """
        UPDATE sync_history
        SET status = :status,
            completed_at = :completedAt,
            error_message = :errorMessage,
            playlists_checked = :playlistsChecked,
            new_tracks_found = :newTracksFound,
            tracks_downloaded = :tracksDownloaded,
            tracks_failed = :tracksFailed,
            bytes_downloaded = :bytesDownloaded
        WHERE id = :id
        """
    )
    suspend fun updateSyncResult(
        id: Long,
        status: SyncState,
        completedAt: Long,
        errorMessage: String? = null,
        playlistsChecked: Int = 0,
        newTracksFound: Int = 0,
        tracksDownloaded: Int = 0,
        tracksFailed: Int = 0,
        bytesDownloaded: Long = 0,
    )
}
