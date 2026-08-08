package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.ArtistImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the cached artist photos backing the Library Artists tab.
 *
 * The write path is entirely worker-owned ([ArtistImageBackfillWorker]); the
 * Library only ever reads, so every query here is cheap and reactive.
 */
@Dao
interface ArtistImageDao {

    /** All cached artist photos — keyed by primary artist name. */
    @Query("SELECT * FROM artist_images")
    fun observeAll(): Flow<List<ArtistImageEntity>>

    /** Every artist name that has already been attempted (photo or sentinel). */
    @Query("SELECT artist_name FROM artist_images")
    suspend fun observedNames(): List<String>

    /**
     * All distinct track artist credits the Library could show, matching the
     * same downloaded-or-streamable scope as `TrackDao.getAllArtists`.
     */
    @Query(
        """
        SELECT DISTINCT artist FROM tracks
        WHERE artist != ''
          AND (is_downloaded = 1 OR is_streamable = 1)
        """,
    )
    suspend fun distinctArtistNames(): List<String>

    /** Insert or replace a photo row ([ArtistImageEntity.artistName] is the key). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtistImageEntity)
}
