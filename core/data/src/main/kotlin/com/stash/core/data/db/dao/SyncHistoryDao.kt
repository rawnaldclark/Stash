package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.stash.core.data.db.entity.SyncHistoryEntity

/** Data-access object for [SyncHistoryEntity]. */
@Dao
interface SyncHistoryDao {

    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT 1")
    suspend fun getLatest(): SyncHistoryEntity?
}
