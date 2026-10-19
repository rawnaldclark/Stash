package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.ArtistGenreKey
import kotlinx.coroutines.flow.Flow

/**
 * The `artist_genres` cache (v60): Last.fm genres per listened artist.
 *
 * Written only by [com.stash.core.data.sync.workers.ArtistGenreWorker]; read by
 * [com.stash.core.data.genre.ArtistGenreRepository] for Top genres. Writes are batched into one
 * transaction so the observed flow re-emits once per batch, not once per artist.
 */
@Dao
interface ArtistGenreDao {

    /** Every cached row, whatever its status. The table holds one row per listened artist. */
    @Query("SELECT * FROM artist_genres")
    fun observeAll(): Flow<List<ArtistGenreEntity>>

    /** The row for one artist key, or null; COLLATE NOCASE like the primary key. */
    @Query("SELECT * FROM artist_genres WHERE artist_name = :artistKey COLLATE NOCASE")
    suspend fun get(artistKey: String): ArtistGenreEntity?

    /**
     * Every artist credit in listening history with its number of listens, in no particular
     * order. Includes plays from another device (they're the user's listening too, and the
     * screens that show genres may count them). Raw credits: [missingArtists] collapses them to
     * artist keys in Kotlin, as [ArtistGenreKey] can't be expressed in SQL.
     */
    @Query(
        """
        SELECT t.artist AS credit, COUNT(*) AS plays
        FROM listening_events e
        JOIN tracks t ON t.id = e.track_id
        WHERE TRIM(t.artist) != ''
        GROUP BY t.artist
        """,
    )
    suspend fun listenedArtistCredits(): List<ArtistCreditPlays>

    /**
     * Artist keys that need no fetch now: genres, a miss or a failure not yet old enough to ask
     * again. COLLATE NOCASE keys; compare case-insensitively.
     */
    @Query(
        """
        SELECT artist_name FROM artist_genres
        WHERE (status = 'TAGGED' AND fetched_at > :taggedSettledAfter)
           OR (status = 'UNTAGGABLE' AND fetched_at > :untaggableSettledAfter)
           OR (status = 'FAILED' AND fetched_at > :failedSettledAfter)
        """,
    )
    suspend fun settledArtistKeys(
        taggedSettledAfter: Long,
        untaggableSettledAfter: Long,
        failedSettledAfter: Long,
    ): List<String>

    /** Empties the cache (Settings › Clear history: the names in it say what was listened to). */
    @Query("DELETE FROM artist_genres")
    suspend fun deleteAll()

    /**
     * Up to [limit] listened artists (as [ArtistGenreKey]s) whose genres should be fetched: no row
     * yet, or a row past its refresh / retry age. Most-listened first, so the artists that weigh
     * most in Top genres are covered first; ties by name for a stable order. Credits that collapse
     * to one key ("Drake", "Drake, Future", "drake") add their listens together.
     *
     * One transaction so the history and the cache are read at the same moment.
     */
    @Transaction
    suspend fun missingArtists(limit: Int, now: Long): List<String> {
        if (limit <= 0) return emptyList()
        val settled = settledArtistKeys(
            taggedSettledAfter = now - ArtistGenreEntity.TAGGED_REFRESH_MS,
            untaggableSettledAfter = now - ArtistGenreEntity.UNTAGGABLE_RETRY_MS,
            failedSettledAfter = now - ArtistGenreEntity.FAILED_RETRY_MS,
        ).mapTo(HashSet()) { it.lowercase() }

        val byKey = HashMap<String, MissingArtist>()
        for (row in listenedArtistCredits()) {
            val key = ArtistGenreKey.of(row.credit)
            if (key.isEmpty()) continue
            val lower = key.lowercase()
            if (lower in settled) continue
            val entry = byKey.getOrPut(lower) { MissingArtist(spelling = key, spellingPlays = row.plays) }
            entry.plays += row.plays
            // The spelling asked of Last.fm: the most-listened credit's, ties to the smaller string,
            // so the result never depends on SQLite's group order.
            if (row.plays > entry.spellingPlays || (row.plays == entry.spellingPlays && key < entry.spelling)) {
                entry.spelling = key
                entry.spellingPlays = row.plays
            }
        }
        return byKey.values
            .sortedWith(compareByDescending<MissingArtist> { it.plays }.thenBy { it.spelling.lowercase() })
            .take(limit)
            .map { it.spelling }
    }


    /** Insert or replace [rows] in one transaction ([ArtistGenreEntity.artistName] is the key). */
    @Transaction
    suspend fun upsertAll(rows: List<ArtistGenreEntity>) {
        if (rows.isNotEmpty()) upsertRows(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRows(rows: List<ArtistGenreEntity>)
}

/** One artist credit and how many listens it has (see [ArtistGenreDao.listenedArtistCredits]). */
data class ArtistCreditPlays(val credit: String, val plays: Long)

/** Accumulator for [ArtistGenreDao.missingArtists]: one artist key, its spelling and listens. */
private class MissingArtist(var spelling: String, var spellingPlays: Long, var plays: Long = 0L)
