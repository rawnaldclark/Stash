package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.stash.core.data.db.entity.SourceAccountEntity
import kotlinx.coroutines.flow.Flow

/** Data-access object for [SourceAccountEntity]. */
@Dao
interface SourceAccountDao {

    @Query("SELECT * FROM source_accounts")
    fun observeAll(): Flow<List<SourceAccountEntity>>
}
