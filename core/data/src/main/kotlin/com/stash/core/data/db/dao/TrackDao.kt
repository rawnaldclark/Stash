package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Data-access object for [TrackEntity]. */
@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY date_added DESC")
    fun observeAll(): Flow<List<TrackEntity>>
}
