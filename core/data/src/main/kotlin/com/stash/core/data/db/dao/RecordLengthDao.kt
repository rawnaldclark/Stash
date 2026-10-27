package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.SQLITE_BIND_LIMIT
import com.stash.core.data.db.entity.RecordLengthEntity

/** Your record's lengths pass: the songs it couldn't time, and when ([RecordLengthEntity]). */
@Dao
interface RecordLengthDao {
    @Query("SELECT * FROM record_lengths")
    suspend fun all(): List<RecordLengthEntity>

    /** Rows written as they are; one already kept under the same key is replaced. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<RecordLengthEntity>)

    @Query("DELETE FROM record_lengths WHERE `key` IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    @Query("DELETE FROM record_lengths")
    suspend fun clear()

    /** [put] and [deleteKeys] together: the mark moves in one step. */
    @Transaction
    suspend fun apply(put: List<RecordLengthEntity>, drop: List<String>) {
        drop.chunked(SQLITE_BIND_LIMIT).forEach { deleteKeys(it) }
        put.chunked(SQLITE_BIND_LIMIT).forEach { put(it) }
    }

    /** Everything kept replaced by [rows]. */
    @Transaction
    suspend fun replace(rows: List<RecordLengthEntity>) {
        clear()
        rows.chunked(SQLITE_BIND_LIMIT).forEach { put(it) }
    }
}
