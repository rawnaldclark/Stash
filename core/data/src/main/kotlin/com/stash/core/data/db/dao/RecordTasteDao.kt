package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.RecordTasteEntity
import com.stash.core.data.db.entity.RecordTasteKeepEntity
import com.stash.core.data.db.entity.RecordTasteSongEntity
import kotlinx.coroutines.flow.Flow

/**
 * Your record's taste (`record_taste`, `record_taste_songs`) and the songs put back into mixes (`record_taste_keep`). The taste is
 * written whole by feature:record's `RecordTaste`, under the record's write lock; the mixes read it through
 * [com.stash.core.data.record.RecordMixTaste].
 */
@Dao
interface RecordTasteDao {
    @Query("SELECT computed_at FROM record_taste WHERE id = ${RecordTasteEntity.ONLY}")
    suspend fun computedAt(): Long?

    /** [computedAt], again each time the taste is written or cleared. */
    @Query("SELECT computed_at FROM record_taste WHERE id = ${RecordTasteEntity.ONLY}")
    fun computedAtFlow(): Flow<Long?>

    @Query("SELECT * FROM record_taste WHERE id = ${RecordTasteEntity.ONLY}")
    suspend fun taste(): RecordTasteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTaste(taste: RecordTasteEntity)

    @Insert
    suspend fun insertSongs(rows: List<RecordTasteSongEntity>)

    @Query("DELETE FROM record_taste_songs")
    suspend fun clearSongs()

    @Query("DELETE FROM record_taste")
    suspend fun clearTaste()

    /** The taste written whole, in one transaction: its songs, then its row (whoever reads sees the old taste or the new one). */
    @Transaction
    suspend fun replace(taste: RecordTasteEntity, songs: List<RecordTasteSongEntity>) {
        clearSongs()
        songs.chunked(INSERT_CHUNK).forEach { insertSongs(it) }
        putTaste(taste)
    }

    /** No taste (Remove import, or the record became empty). The songs put back are [clearKeep]'s. */
    @Transaction
    suspend fun clear() {
        clearSongs()
        clearTaste()
    }

    /** What a mix reads of a song: its key, weight and whether the taste keeps it out. */
    data class MixRow(
        @ColumnInfo(name = "key") val key: String,
        @ColumnInfo(name = "weight") val weight: Double,
        @ColumnInfo(name = "avoid") val avoid: Boolean,
    )

    @Query("SELECT `key`, weight, avoid FROM record_taste_songs")
    suspend fun mixRows(): List<MixRow>

    /** One taste as the mixes read it: when it was worked out, its body and its songs, read together. */
    data class Snapshot(val computedAt: Long, val body: String, val songs: List<MixRow>)

    @Transaction
    suspend fun snapshot(): Snapshot? {
        val t = taste() ?: return null
        return Snapshot(t.computedAt, t.body, mixRows())
    }

    /** The songs the taste keeps out of mixes, in its order (put-back ones still among them: the reader takes them away). */
    @Query("SELECT * FROM record_taste_songs WHERE avoid = 1 ORDER BY rank")
    suspend fun avoided(): List<RecordTasteSongEntity>

    @Query("SELECT COUNT(*) FROM record_taste_songs")
    suspend fun songCount(): Int

    /** The songs put back into mixes, in no order (sort them in Kotlin, never by SQLite). */
    @Query("SELECT `key` FROM record_taste_keep")
    suspend fun keepKeys(): List<String>

    /** [keepKeys], again each time a song is put back or the table is cleared. */
    @Query("SELECT `key` FROM record_taste_keep")
    fun keepKeysFlow(): Flow<List<String>>

    /** A song put back; one put back already stays as it was (each once). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeep(row: RecordTasteKeepEntity): Long

    @Query("DELETE FROM record_taste_keep")
    suspend fun clearKeep()
}

/** Songs written at a time (one statement each; the chunks only bound what one call holds). */
private const val INSERT_CHUNK = 2_000
