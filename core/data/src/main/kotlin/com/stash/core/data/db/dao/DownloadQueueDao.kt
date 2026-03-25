package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.stash.core.data.db.entity.DownloadQueueEntity
import kotlinx.coroutines.flow.Flow

/** Data-access object for [DownloadQueueEntity]. */
@Dao
interface DownloadQueueDao {

    @Query("SELECT * FROM download_queue WHERE status = 'PENDING' ORDER BY created_at ASC")
    fun observePending(): Flow<List<DownloadQueueEntity>>
}
