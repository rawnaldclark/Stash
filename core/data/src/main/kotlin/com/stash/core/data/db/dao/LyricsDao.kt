package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.LyricsEntity
import kotlinx.coroutines.flow.Flow

/**
 * v0.9.36: room access for the `lyrics` table. One lyrics row per
 * track id; absence of a row + `tracks.lyrics_fetched_at IS NULL`
 * means "never tried"; absence + `lyrics_fetched_at = 0L` means
 * "tried and got nothing". Row presence implies a successful fetch
 * with `lyrics_fetched_at` stamped non-zero.
 *
 * Pair every `upsert` with a [TrackDao.setLyricsFetchedAt] call to
 * stamp the success epoch-millis on the parent row; pair every
 * "tried and got nothing" outcome with `setLyricsFetchedAt(id, 0L)`
 * WITHOUT inserting a lyrics row. The backfill worker's
 * `lyrics_fetched_at IS NULL` predicate then filters both outcomes
 * out so it terminates.
 */
@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    suspend fun get(trackId: Long): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    fun observe(trackId: Long): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE track_id = :trackId")
    suspend fun delete(trackId: Long)

    /** Rows the TTML upgrade backfill still has to try: real lyrics, no TTML, never definitively missed. */
    @Query("SELECT track_id FROM lyrics WHERE ttml IS NULL AND instrumental = 0 AND ttml_checked_at IS NULL")
    suspend fun trackIdsPendingTtml(): List<Long>

    @Query("UPDATE lyrics SET ttml_checked_at = :at WHERE track_id = :trackId")
    suspend fun markTtmlChecked(trackId: Long, at: Long)

    /**
     * Downloaded tracks with no lyrics: never tried (NULL) or previously a miss (0L). In id order:
     * `LyricsRepository.trackIdsMissingLyrics` resumes the bulk fetch from an id cursor.
     */
    @Query("SELECT id FROM tracks WHERE is_downloaded = 1 AND (lyrics_fetched_at IS NULL OR lyrics_fetched_at = 0) ORDER BY id")
    suspend fun trackIdsMissingLyrics(): List<Long>

    /** Rows currently carrying word-synced TTML — used when the user switches to LRC-only, to know which to clean up. */
    @Query("SELECT track_id FROM lyrics WHERE ttml IS NOT NULL")
    suspend fun trackIdsWithTtml(): List<Long>

    /** Wipes TTML off every row that has it. Returns the number of rows changed. */
    @Query("UPDATE lyrics SET ttml = NULL, ttml_checked_at = NULL WHERE ttml IS NOT NULL")
    suspend fun clearAllTtml(): Int

    /** Null when no row exists yet (never fetched) — callers treat that as "no offset set". */
    @Query("SELECT sync_offset_ms FROM lyrics WHERE track_id = :trackId")
    fun observeSyncOffsetMs(trackId: Long): Flow<Long?>

    @Query("UPDATE lyrics SET sync_offset_ms = :offsetMs WHERE track_id = :trackId")
    suspend fun setSyncOffsetMs(trackId: Long, offsetMs: Long)
}
