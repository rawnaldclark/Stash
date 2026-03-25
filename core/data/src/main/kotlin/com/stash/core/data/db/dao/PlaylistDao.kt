package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.stash.core.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

/** Data-access object for [PlaylistEntity]. */
@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists WHERE is_active = 1 ORDER BY name ASC")
    fun observeActive(): Flow<List<PlaylistEntity>>
}
