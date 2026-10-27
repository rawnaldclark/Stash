package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.SQLITE_BIND_LIMIT
import com.stash.core.data.db.entity.RecordHiddenEntity

/** Your record's songs hidden on Spotify ([RecordHiddenEntity]). */
@Dao
interface RecordHiddenDao {
    /** Every key, in no order (archive.ts keeps them sorted by UTF-16 units: sort them in Kotlin, never by SQLite). */
    @Query("SELECT `key` FROM record_hidden")
    suspend fun keys(): List<String>

    @Query("SELECT * FROM record_hidden")
    suspend fun all(): List<RecordHiddenEntity>

    @Query("SELECT COUNT(*) FROM record_hidden")
    suspend fun count(): Int

    /** A song kept already stays as it was first written (its first spelling). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<RecordHiddenEntity>)

    /** [rows] added to what is kept, in one go. */
    @Transaction
    suspend fun add(rows: List<RecordHiddenEntity>) {
        rows.chunked(SQLITE_BIND_LIMIT).forEach { insert(it) }
    }

    @Query("DELETE FROM record_hidden")
    suspend fun clear()
}
