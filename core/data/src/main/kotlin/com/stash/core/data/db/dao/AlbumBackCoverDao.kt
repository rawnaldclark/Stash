package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.AlbumBackCoverEntity
import kotlinx.coroutines.flow.Flow

/** The album back covers Now Playing flips to. Written only by `AlbumBackCoverRepository`. */
@Dao
interface AlbumBackCoverDao {

    /** One album's row, or null when it has never been looked up. */
    @Query("SELECT * FROM album_back_covers WHERE album_key = :albumKey COLLATE NOCASE")
    fun observe(albumKey: String): Flow<AlbumBackCoverEntity?>

    @Query("SELECT * FROM album_back_covers WHERE album_key = :albumKey COLLATE NOCASE")
    suspend fun get(albumKey: String): AlbumBackCoverEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AlbumBackCoverEntity)
}
