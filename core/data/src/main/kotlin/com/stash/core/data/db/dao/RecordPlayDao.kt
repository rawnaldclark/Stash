package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.SQLITE_BIND_LIMIT
import com.stash.core.data.db.dao.WebLibraryExportDao.OwnAlbumTotal
import com.stash.core.data.db.entity.RecordPlayEntity
import kotlinx.coroutines.flow.Flow

/**
 * Your record's imported plays ([RecordPlayEntity], `record_plays`): what an import writes, what Remove import clears, and what
 * the record and its Home card read. Read a page at a time by id (the primary key), so a decade of plays is never all in memory.
 */
@Dao
interface RecordPlayDao {

    /** Rows written as they are; one already kept under the same id is replaced (IndexedDB's `put`). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<RecordPlayEntity>)

    @Query("DELETE FROM record_plays WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    /**
     * One batch of an import, in one transaction: [add] written, then [drop] removed (an import writes adds before drops, so one
     * stopped halfway leaves an Account play beside its Extended twin, which the next import removes, and never loses a play).
     */
    @Transaction
    suspend fun apply(add: List<RecordPlayEntity>, drop: List<String>) {
        if (add.isNotEmpty()) put(add)
        drop.chunked(SQLITE_BIND_LIMIT).forEach { deleteIds(it) }
    }

    /** Remove import: every imported play goes. */
    @Query("DELETE FROM record_plays")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM record_plays")
    suspend fun count(): Int

    /** Up to [limit] rows after [after] in id order (`""` for the first page): the record's archive, a page at a time. */
    @Query("SELECT * FROM record_plays WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun pageAfter(after: String, limit: Int): List<RecordPlayEntity>

    /** What a merge needs of a kept play: its id, title, start, time played and flags (never the artist, album or Spotify id). */
    data class KeptRow(val id: String, val t: String, val s: Long, val ms: Long, val f: Int)

    /** [pageAfter], with only what a merge needs ([KeptRow]). */
    @Query("SELECT id, t, s, ms, f FROM record_plays WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun keptAfter(after: String, limit: Int): List<KeptRow>

    /**
     * The archive's listens (30 s or more) by source (Last.fm, Stash on the phone, the rest Spotify's), and the first and last
     * listen's start (null with none): what the import record counts.
     */
    data class Listens(
        val spotify: Int,
        val lastfm: Int,
        val stash: Int,
        @ColumnInfo(name = "first_start") val first: Long?,
        @ColumnInfo(name = "last_start") val last: Long?,
    )

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN (f & 64) = 0 AND (f & 256) = 0 THEN 1 ELSE 0 END), 0) AS spotify,
            COALESCE(SUM(CASE WHEN (f & 64) != 0 THEN 1 ELSE 0 END), 0) AS lastfm,
            COALESCE(SUM(CASE WHEN (f & 64) = 0 AND (f & 256) != 0 THEN 1 ELSE 0 END), 0) AS stash,
            MIN(s) AS first_start, MAX(s) AS last_start
        FROM record_plays WHERE ms >= $LISTEN_MS
        """,
    )
    suspend fun listens(): Listens

    /**
     * The imported plays from far off: how many, the time they hold and the newest start. An import or a removal changes it;
     * Your record's Home card reads its rows again only then.
     */
    data class Stamp(
        @ColumnInfo(name = "row_count") val rows: Int,
        val ms: Long,
        @ColumnInfo(name = "last_start") val lastStart: Long,
    )

    /** [Stamp], again each time Room sees `record_plays` written. Cheap: one pass over a table with no joins. */
    @Query("SELECT COUNT(*) AS row_count, COALESCE(SUM(ms), 0) AS ms, COALESCE(MAX(s), 0) AS last_start FROM record_plays")
    fun stamp(): Flow<Stamp>

    /**
     * The imported listens (30 s or more) totalled by artist and album as written, for the Home card's sleeve: the album played
     * last first, as [WebLibraryExportDao.ownAlbumTotals] has them (an export carries no covers: [OwnAlbumTotal.albumArtUrl] is
     * null). Plays with no album are left out.
     */
    @Query(
        """
        SELECT a AS artist, al AS album, MAX(t) AS title, NULL AS album_art_url, COUNT(*) AS plays,
               SUM(ms) AS listened_ms, MIN(s) AS first_at, MAX(s) AS last_at
        FROM record_plays
        WHERE al IS NOT NULL AND al != '' AND ms >= $LISTEN_MS
        GROUP BY a, al
        ORDER BY last_at DESC, a ASC, al ASC
        """,
    )
    suspend fun albumTotals(): List<OwnAlbumTotal>

    companion object {
        /** A listen, not a skip: 30 s or more (plays.ts `LISTEN_MS`). */
        const val LISTEN_MS = 30_000L
    }
}
