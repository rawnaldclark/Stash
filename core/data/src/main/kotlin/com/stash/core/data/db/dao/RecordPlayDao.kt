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

    /**
     * [pageAfter] for one kind of row: the Last.fm scrobbles ([scrobbles] true) or every other play. The record reads the two
     * apart, with this phone's own plays between them, so the scrobbles a play made here covers can be left out as they pass.
     */
    @Query("SELECT * FROM record_plays WHERE id > :after AND ((f & $LASTFM) != 0) = :scrobbles ORDER BY id LIMIT :limit")
    suspend fun pageAfterOf(after: String, scrobbles: Boolean, limit: Int): List<RecordPlayEntity>

    /** Of [ids] (at most [SQLITE_BIND_LIMIT]), the ones kept already. */
    @Query("SELECT id FROM record_plays WHERE id IN (:ids)")
    suspend fun keptIds(ids: List<String>): List<String>

    /** A kept play's start and time played. */
    data class Span(val s: Long, val ms: Long)

    /**
     * The plays that aren't scrobbles whose id lies in [from, until): with `from = "<folded title>|"` and `until = "<folded
     * title>}"` (`}` follows `|`), every such play of that title, since an id is `fold(title)|fold(artist)|start` and a folded
     * title has no `|`. A range on the primary key, so it reads only that title's rows.
     */
    @Query("SELECT s, ms FROM record_plays WHERE id >= :from AND id < :until AND (f & $LASTFM) = 0")
    suspend fun playsIn(from: String, until: String): List<Span>

    /** A song's words and the longest time a play of it ran. */
    data class SongLength(val t: String, val a: String, val ms: Long)

    /**
     * The longest play that ran to its end ([DONE]), by the words as written, of every song that isn't a scrobble: what a
     * scrobble's length is taken from (plays.ts `knownLengths`, which keys these by song key, folded by the caller).
     */
    @Query("SELECT t, a, MAX(ms) AS ms FROM record_plays WHERE (f & $DONE) != 0 AND (f & $LASTFM) = 0 GROUP BY t, a")
    suspend fun doneLengths(): List<SongLength>

    /** Remove your Last.fm scrobbles: every scrobble goes, the other plays stay. Returns how many went. */
    @Query("DELETE FROM record_plays WHERE (f & $LASTFM) != 0")
    suspend fun deleteScrobbles(): Int

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

    /**
     * [albumTotals] of the plays that aren't scrobbles (the Spotify export's): the Home card adds the scrobbles' albums itself,
     * leaving out the ones a play made here covers, and each only while its source is switched on.
     */
    @Query(
        """
        SELECT a AS artist, al AS album, MAX(t) AS title, NULL AS album_art_url, COUNT(*) AS plays,
               SUM(ms) AS listened_ms, MIN(s) AS first_at, MAX(s) AS last_at
        FROM record_plays
        WHERE al IS NOT NULL AND al != '' AND ms >= $LISTEN_MS AND (f & $LASTFM) = 0
        GROUP BY a, al
        ORDER BY last_at DESC, a ASC, al ASC
        """,
    )
    suspend fun albumTotalsWithoutScrobbles(): List<OwnAlbumTotal>

    companion object {
        /** A listen, not a skip: 30 s or more (plays.ts `LISTEN_MS`). */
        const val LISTEN_MS = 30_000L

        /** plays.ts `F_DONE`: the play ran to its end. */
        const val DONE = 2

        /** plays.ts `F_LASTFM`: a Last.fm scrobble. */
        const val LASTFM = 64
    }
}
