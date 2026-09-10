package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.ArtistImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the cached artist photos backing the Library Artists tab.
 *
 * The write path is entirely worker-owned ([ArtistImageBackfillWorker]); the
 * Library only ever reads. Reads are targeted per-artist ([observeByName],
 * COLLATE NOCASE) so the detail screen subscribes to one row instead of the
 * whole table, and writes are batched into a single transaction so a backfill
 * pass re-emits the observable flow once instead of once per artist.
 */
@Dao
interface ArtistImageDao {

    /** All cached artist photos — keyed by primary artist name. */
    @Query("SELECT * FROM artist_images")
    fun observeAll(): Flow<List<ArtistImageEntity>>

    /**
     * The photo row for one primary-artist name, or null when that name has
     * never been attempted. COLLATE NOCASE — a case variant of the stored key
     * ("aarne" vs "Aarne") still resolves, matching the worker's case-
     * insensitive collapse.
     */
    @Query("SELECT * FROM artist_images WHERE artist_name = :name COLLATE NOCASE")
    fun observeByName(name: String): Flow<ArtistImageEntity?>

    /** Every artist name that has already been attempted (photo or sentinel). */
    @Query("SELECT artist_name FROM artist_images")
    suspend fun observedNames(): List<String>

    /**
     * Every distinct track artist credit the Library Artists tab can show, in
     * the same scope as `TrackDao.getAllArtists`: downloads in every mode, plus
     * stream-only rows when streaming is on. The playlist-membership arm also
     * keeps still-unchecked streamable stubs (`is_streamable_checked_at` null)
     * in the set — a freshly-synced playlist's artists get photos before the
     * availability worker has probed them, matching how the tab renders
     * playlists in Online mode (membership is the source of truth).
     */
    @Query(
        """
        SELECT DISTINCT artist FROM tracks
        WHERE artist != ''
          AND (
              is_downloaded = 1
              OR is_streamable = 1
              OR EXISTS (
                  SELECT 1
                  FROM playlist_tracks pt
                  JOIN playlists p ON p.id = pt.playlist_id
                  WHERE pt.track_id = tracks.id
                    AND pt.removed_at IS NULL
                    AND p.is_active = 1
              )
          )
        """,
    )
    suspend fun distinctArtistNames(): List<String>

    /**
     * Insert or replace photo rows in ONE transaction ([ArtistImageEntity.artistName]
     * is the key). Batching the whole pass into a single write keeps
     * [observeAll]/[observeByName] from re-emitting the flow per upsert.
     */
    @Transaction
    suspend fun upsertAll(entities: List<ArtistImageEntity>) {
        upsertRows(entities)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRows(entities: List<ArtistImageEntity>)
}