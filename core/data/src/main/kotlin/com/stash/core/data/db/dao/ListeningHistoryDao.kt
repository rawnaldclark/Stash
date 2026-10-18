package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.stash.core.data.db.chunkedForBindWrite
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.listening.ListeningDay
import kotlinx.coroutines.flow.Flow

/**
 * The read queries of [ListeningHistoryDao], as constants so `ListeningHistoryQueryPlanTest` can run
 * EXPLAIN QUERY PLAN on exactly the SQL Room runs.
 *
 * Every day-range read starts from [LE], the listens of the range read straight off the covering
 * v59 index (local_day, track_id, started_at, listened_ms, local_minute): no table row is touched,
 * and songs are joined only after grouping (one lookup per song, not per listen). Measured on the
 * Pixel 5 rig with 60k listens, an all-time read is tens of milliseconds this way; reading table rows
 * per listen (an `OR` day filter, a per-listen join) made the same reads 5-10x slower, and the
 * earlier-listen check of [NEW_TRACKS] as a correlated NOT EXISTS was quadratic.
 *
 * What a day is, shared by every query so History, Top songs, Stats and Your Year agree: the
 * listen's local day (v59); a row not yet stamped by ListeningDayBackfill counts on its UTC day.
 */
internal object ListeningHistorySql {
    /** A listen's day, for the few queries that read table rows (History, deletes). */
    const val DAY = "COALESCE(le.local_day, le.started_at / 86400000)"

    /** "The listen's day is in [:fromDay, :toDay]" over table rows (privacy deletes only). */
    const val IN_DAYS =
        "(le.local_day BETWEEN :fromDay AND :toDay OR (le.local_day IS NULL " +
            "AND le.started_at >= :fromDay * 86400000 AND le.started_at < (:toDay + 1) * 86400000))"

    /**
     * The listens with a day in [:fromDay, :toDay] as (day, track_id, started_at, listened_ms,
     * minute): stamped rows from the covering index, then the unstamped ones by their UTC day and
     * minute (normally none; an indexed `local_day IS NULL` lookup). UNION ALL rather than OR: an OR
     * makes SQLite fetch every table row by rowid.
     */
    const val LE =
        "(SELECT local_day AS day, track_id, started_at, listened_ms, " +
            "COALESCE(local_minute, (started_at % 86400000) / 60000) AS minute " +
            "FROM listening_events WHERE local_day BETWEEN :fromDay AND :toDay " +
            "UNION ALL SELECT started_at / 86400000, track_id, started_at, listened_ms, (started_at % 86400000) / 60000 " +
            "FROM listening_events WHERE local_day IS NULL " +
            "AND started_at >= :fromDay * 86400000 AND started_at < (:toDay + 1) * 86400000)"

    /** Songs with any listen on a day before :fromDay (the same day rule as [LE]). */
    const val HEARD_BEFORE =
        "(SELECT track_id FROM listening_events WHERE local_day < :fromDay " +
            "UNION ALL SELECT track_id FROM listening_events WHERE local_day IS NULL AND started_at < :fromDay * 86400000)"

    /**
     * Per song in the range: listens, the last start, the known listening time and how many listens
     * have none (those count the song's length, owner decision 5).
     */
    const val PER_TRACK =
        "(SELECT le.track_id AS tid, COUNT(*) AS plays, MAX(le.started_at) AS lastAt, " +
            "SUM(COALESCE(le.listened_ms, 0)) AS known, SUM(le.listened_ms IS NULL) AS unknown " +
            "FROM " + LE + " le GROUP BY le.track_id)"

    /** A listen's time: the real time, else its song's length (looked up only then), else 0. */
    const val LISTENED = "COALESCE(le.listened_ms, (SELECT d.duration_ms FROM tracks d WHERE d.id = le.track_id), 0)"

    const val HISTORY_PAGE =
        """
        SELECT le.* FROM listening_events le
        INNER JOIN tracks t ON t.id = le.track_id
        WHERE le.started_at <= :beforeStartedAt
          AND (le.started_at < :beforeStartedAt OR le.id < :beforeId)
          AND (:pattern IS NULL
               OR t.title LIKE :pattern ESCAPE '\'
               OR t.artist LIKE :pattern ESCAPE '\'
               OR t.album LIKE :pattern ESCAPE '\')
        ORDER BY le.started_at DESC, le.id DESC
        LIMIT :limit
        """

    const val DAY_COUNTS =
        "SELECT le.day AS day, COUNT(*) AS plays, SUM(" + LISTENED + ") AS listenedMs " +
            "FROM " + LE + " le GROUP BY le.day ORDER BY le.day"

    const val TOP_TRACKS =
        "SELECT t.*, a.plays AS plays, a.lastAt AS lastPlayedAt " +
            "FROM " + PER_TRACK + " a INNER JOIN tracks t ON t.id = a.tid " +
            "ORDER BY a.plays DESC, a.lastAt DESC, a.tid ASC LIMIT :limit"

    const val TOP_ARTISTS =
        "SELECT t.artist AS artist, SUM(a.plays) AS plays, COUNT(*) AS songs, MAX(a.lastAt) AS lastPlayedAt, " +
            "t.album_art_url AS albumArtUrl, t.album_art_path AS albumArtPath " +
            "FROM " + PER_TRACK + " a INNER JOIN tracks t ON t.id = a.tid " +
            "GROUP BY LOWER(t.artist) ORDER BY plays DESC, lastPlayedAt DESC, LOWER(t.artist) ASC LIMIT :limit"

    const val TOP_ALBUMS =
        "SELECT t.album AS album, COALESCE(NULLIF(t.album_artist, ''), t.artist) AS artist, " +
            "SUM(a.plays) AS plays, COUNT(*) AS songs, MAX(a.lastAt) AS lastPlayedAt, " +
            "t.album_art_url AS albumArtUrl, t.album_art_path AS albumArtPath " +
            "FROM " + PER_TRACK + " a INNER JOIN tracks t ON t.id = a.tid WHERE t.album != '' " +
            "GROUP BY LOWER(t.album), LOWER(COALESCE(NULLIF(t.album_artist, ''), t.artist)) " +
            "ORDER BY plays DESC, lastPlayedAt DESC LIMIT :limit"

    const val TOTALS =
        "SELECT COALESCE(SUM(a.plays), 0) AS plays, COUNT(*) AS songs, " +
            "COUNT(DISTINCT LOWER(t.artist)) AS artists, " +
            "(SELECT COUNT(DISTINCT d.day) FROM " + LE + " d) AS days, " +
            "COALESCE(SUM(a.known + a.unknown * t.duration_ms), 0) AS listenedMs, " +
            "COALESCE(SUM(a.unknown), 0) AS estimatedPlays " +
            "FROM " + PER_TRACK + " a INNER JOIN tracks t ON t.id = a.tid"

    const val HOUR_HISTOGRAM =
        "SELECT le.minute / 60 AS hour, COUNT(*) AS plays FROM " + LE + " le GROUP BY hour ORDER BY hour"

    const val NEW_TRACKS =
        "SELECT t.*, a.plays AS plays, a.lastAt AS lastPlayedAt " +
            "FROM (SELECT le.track_id AS tid, COUNT(*) AS plays, MAX(le.started_at) AS lastAt FROM " + LE + " le " +
            "WHERE le.track_id NOT IN " + HEARD_BEFORE + " GROUP BY le.track_id) a " +
            "INNER JOIN tracks t ON t.id = a.tid ORDER BY a.plays DESC, a.lastAt DESC, a.tid ASC LIMIT :limit"

    const val COUNT_NEW_TRACKS =
        "SELECT COUNT(DISTINCT le.track_id) FROM " + LE + " le WHERE le.track_id NOT IN " + HEARD_BEFORE

    const val OBSESSION =
        "SELECT t.*, o.day AS day, o.plays AS plays " +
            "FROM (SELECT le.day AS day, le.track_id AS tid, COUNT(*) AS plays FROM " + LE + " le " +
            "GROUP BY le.day, le.track_id ORDER BY plays DESC, le.day DESC, le.track_id ASC LIMIT 1) o " +
            "INNER JOIN tracks t ON t.id = o.tid"

    /**
     * The smallest listen day: the lowest stamped day (one index read) or the lowest UTC day of the
     * unstamped rows. Not "the day of the earliest start": after travel west, or with stamped and
     * unstamped rows mixed, a later start can sit on an earlier day.
     */
    const val FIRST_LISTEN_DAY =
        "SELECT MIN(d) FROM (SELECT MIN(local_day) AS d FROM listening_events " +
            "UNION ALL SELECT MIN(started_at) / 86400000 FROM listening_events WHERE local_day IS NULL)"

    /**
     * The backfill's batch: unstamped rows after :afterId, walking the table by rowid (`+` keeps
     * SQLite off the day index, which would sort the remaining unstamped rows on every batch).
     */
    const val UNSTAMPED_AFTER =
        "SELECT id, started_at AS startedAt FROM listening_events " +
            "WHERE +local_day IS NULL AND id > :afterId ORDER BY id LIMIT :limit"

    const val YEARS =
        "SELECT DISTINCT CAST(strftime('%Y', d * 86400, 'unixepoch') AS INTEGER) AS year " +
            "FROM (SELECT DISTINCT COALESCE(local_day, started_at / 86400000) AS d FROM listening_events) " +
            "ORDER BY year DESC"
}

/**
 * Reads (and privacy deletes) for the "Your listening" surfaces: History, Top songs, Stats and Your
 * Year. Every query here is over **all** listens, this phone's and those from another device
 * (`origin_device` set: Stash on the web, the mirror, later an import): History shows them, and the
 * owner chose to count them in Top / Stats / Year (2026-10-18, decision 3). They still never feed
 * Stash Mixes, `tracks.play_count`, auto-save or a scrobble destination — those queries live in
 * [ListeningEventDao] with `origin_device IS NULL` (`RemotePlaysDaoTest`). Nothing here may be used
 * to rank taste for a mix.
 *
 * Every row in `listening_events` is a listen: the recorder writes one only once the song has
 * played long enough to count (half the song, 30 s..4 min), so there is no `completed_at` filter.
 * (Plays from another device have `completed_at` NULL, as do listens from before v18; a filter
 * would drop both.)
 *
 * Days are epoch-days in the listener's zone ([ListeningDay]); ranges are inclusive.
 */
@Dao
interface ListeningHistoryDao {

    // ── History ───────────────────────────────────────────────────────

    /**
     * One page of History, newest first, strictly older than the cursor (the last row of the page
     * before): `started_at DESC, id DESC`, so two listens with the same start never repeat or drop
     * across pages. First page: `beforeStartedAt = Long.MAX_VALUE, beforeId = Long.MAX_VALUE`.
     *
     * [pattern] filters on title, artist or album: a LIKE pattern with `\` as the escape character
     * (build it with [likePattern]), or null for no filter.
     */
    @Transaction
    @Query(ListeningHistorySql.HISTORY_PAGE)
    suspend fun historyPage(beforeStartedAt: Long, beforeId: Long, pattern: String?, limit: Int): List<HistoryRow>

    /**
     * Listens and listening time per day in [fromDay]..[toDay], oldest day first; days without a
     * listen are absent. Feeds day headers, the calendar, heatmap, chart, streaks and busiest day.
     */
    @Query(ListeningHistorySql.DAY_COUNTS)
    fun observeDayCounts(fromDay: Long, toDay: Long): Flow<List<DayCount>>

    // ── Top ───────────────────────────────────────────────────────────

    /** Most-listened songs in the range; ties go to the one heard last, then the older row. */
    @Query(ListeningHistorySql.TOP_TRACKS)
    fun observeTopTracks(fromDay: Long, toDay: Long, limit: Int = 100): Flow<List<TopTrackRow>>

    /**
     * Most-listened artists in the range, grouped case-insensitively by the song's artist string
     * (the same rule as [ListeningEventDao.getTopArtistsSince]). The name and cover come from the
     * artist's most recent listen in the range (SQLite takes bare columns from the MAX() row).
     */
    @Query(ListeningHistorySql.TOP_ARTISTS)
    fun observeTopArtists(fromDay: Long, toDay: Long, limit: Int = 100): Flow<List<TopArtistRow>>

    /**
     * Most-listened albums in the range: one per (album, album artist or else artist), ignoring
     * case; songs without an album are left out.
     */
    @Query(ListeningHistorySql.TOP_ALBUMS)
    fun observeTopAlbums(fromDay: Long, toDay: Long, limit: Int = 100): Flow<List<TopAlbumRow>>

    // ── Stats ─────────────────────────────────────────────────────────

    /**
     * Totals for the range. Listening time is the real time where known, else the song's length;
     * [ListeningTotals.estimatedPlays] says how many listens were estimated that way.
     */
    @Query(ListeningHistorySql.TOTALS)
    fun observeTotals(fromDay: Long, toDay: Long): Flow<ListeningTotals>

    /**
     * Listens per hour of the listener's day (0..23) in the range, hours without a listen absent.
     * A row not yet stamped counts at its UTC hour until the backfill reaches it.
     */
    @Query(ListeningHistorySql.HOUR_HISTOGRAM)
    fun observeHourHistogram(fromDay: Long, toDay: Long): Flow<List<HourCount>>

    // ── Your Year ─────────────────────────────────────────────────────

    /**
     * Songs first ever heard in the range (no listen on an earlier day), most listened first: the
     * year's discoveries.
     */
    @Query(ListeningHistorySql.NEW_TRACKS)
    suspend fun newTracksIn(fromDay: Long, toDay: Long, limit: Int = 100): List<TopTrackRow>

    /** How many songs were first ever heard in the range (see [newTracksIn]). */
    @Query(ListeningHistorySql.COUNT_NEW_TRACKS)
    suspend fun countNewTracksIn(fromDay: Long, toDay: Long): Int

    /** The song played most times in one day within the range (latest such day on a tie), or null. */
    @Query(ListeningHistorySql.OBSESSION)
    suspend fun obsession(fromDay: Long, toDay: Long): ObsessionRow?

    /** The earliest day with a listen (see [ListeningHistorySql.FIRST_LISTEN_DAY]), or null. */
    @Query(ListeningHistorySql.FIRST_LISTEN_DAY)
    suspend fun firstListenDay(): Long?

    /** Every calendar year with a listen, newest first (an epoch-day * 86400 is that date at UTC midnight). */
    @Query(ListeningHistorySql.YEARS)
    suspend fun yearsWithListens(): List<Int>

    // ── Privacy deletes ───────────────────────────────────────────────
    //
    // A delete takes the listen's unsent scrobble outcomes with it (listen_submissions cascades)
    // and recomputes play_count / last_played of the songs it touched, so Library's Most played
    // and Recently played forget it too. Nothing already sent to Last.fm / ListenBrainz / YouTube
    // Music is touched. These remove this phone's copy only.

    /** Deletes one listen. Returns how many rows went (0 or 1). */
    @Transaction
    suspend fun deleteEvent(eventId: Long): Int {
        val trackId = trackIdOfEvent(eventId) ?: return 0
        val n = deleteEventRaw(eventId)
        recomputeTrackStats(listOf(trackId))
        return n
    }

    /** Deletes every listen on [day]. */
    suspend fun deleteDay(day: Long): Int = deleteBetween(day, day)

    /** Deletes every listen on the days [fromDay]..[toDay]. */
    @Transaction
    suspend fun deleteBetween(fromDay: Long, toDay: Long): Int {
        val trackIds = trackIdsBetween(fromDay, toDay)
        val n = deleteBetweenRaw(fromDay, toDay)
        recomputeTrackStats(trackIds)
        return n
    }

    /** Deletes the whole history. */
    @Transaction
    suspend fun deleteAll(): Int {
        val trackIds = allTrackIds()
        val n = deleteAllRaw()
        recomputeTrackStats(trackIds)
        return n
    }

    /**
     * Deletes every listen whose `origin_device` is [origin] (the debug seeder's rows; later an
     * import the user takes back). Such rows never count in play_count, so nothing to recompute.
     */
    @Query("DELETE FROM listening_events WHERE origin_device = :origin")
    suspend fun deleteFromOrigin(origin: String): Int

    /**
     * Rebuilds `play_count` and `last_played` of [trackIds] from what is left in the history: this
     * phone's listens only (`origin_device IS NULL`), the same rule as
     * [ListeningEventDao.backfillMissingTrackStats]. A song with none left goes back to 0 / NULL.
     */
    suspend fun recomputeTrackStats(trackIds: List<Long>) {
        trackIds.distinct().chunkedForBindWrite { recomputeTrackStatsRaw(it) }
    }

    @Query(
        """
        UPDATE tracks
        SET play_count = (
                SELECT COUNT(*) FROM listening_events
                WHERE track_id = tracks.id AND origin_device IS NULL
            ),
            last_played = (
                SELECT MAX(COALESCE(completed_at, started_at)) FROM listening_events
                WHERE track_id = tracks.id AND origin_device IS NULL
            )
        WHERE id IN (:trackIds)
        """,
    )
    suspend fun recomputeTrackStatsRaw(trackIds: List<Long>)

    @Query("SELECT track_id FROM listening_events WHERE id = :eventId")
    suspend fun trackIdOfEvent(eventId: Long): Long?

    @Query("SELECT DISTINCT le.track_id FROM listening_events le WHERE " + ListeningHistorySql.IN_DAYS)
    suspend fun trackIdsBetween(fromDay: Long, toDay: Long): List<Long>

    @Query("SELECT DISTINCT track_id FROM listening_events")
    suspend fun allTrackIds(): List<Long>

    @Query("DELETE FROM listening_events WHERE id = :eventId")
    suspend fun deleteEventRaw(eventId: Long): Int

    @Query("DELETE FROM listening_events WHERE id IN (SELECT le.id FROM listening_events le WHERE " + ListeningHistorySql.IN_DAYS + ")")
    suspend fun deleteBetweenRaw(fromDay: Long, toDay: Long): Int

    @Query("DELETE FROM listening_events")
    suspend fun deleteAllRaw(): Int

    // ── Day stamps for rows from before v59 (ListeningDayBackfill) ────

    /** Up to [limit] rows still without a local day, after [afterId] in id order. */
    @Query(ListeningHistorySql.UNSTAMPED_AFTER)
    suspend fun unstampedAfter(afterId: Long, limit: Int): List<UnstampedListen>

    @Query(
        "UPDATE listening_events SET local_day = :day, local_minute = :minute " +
            "WHERE id = :eventId AND local_day IS NULL",
    )
    suspend fun stamp(eventId: Long, day: Long, minute: Int): Int

    /** Stamps a batch in one transaction (one fsync per batch, not per row). */
    @Transaction
    suspend fun stampAll(stamps: List<EventStamp>) {
        for (s in stamps) stamp(s.eventId, s.day, s.minute)
    }

    @Query("SELECT COUNT(*) FROM listening_events WHERE local_day IS NULL")
    suspend fun countUnstamped(): Int

    companion object {
        /**
         * A History search pattern: "contains [query]", with LIKE's `%` and `_` (and the `\` escape
         * itself) taken literally. Null for a blank query.
         */
        fun likePattern(query: String?): String? {
            val q = query?.trim().orEmpty()
            if (q.isEmpty()) return null
            val escaped = buildString {
                for (c in q) {
                    if (c == '\\' || c == '%' || c == '_') append('\\')
                    append(c)
                }
            }
            return "%$escaped%"
        }
    }
}

/** One History row: the listen and its song (every listen has one: deleting a song cascades). */
data class HistoryRow(
    @Embedded val event: ListeningEventEntity,
    @Relation(parentColumn = "track_id", entityColumn = "id")
    val track: TrackEntity,
)

/** The listen's local day, falling back to its UTC day while the backfill hasn't reached it. */
val ListeningEventEntity.day: Long get() = localDay ?: (startedAt / ListeningDay.MS_PER_DAY)

data class DayCount(
    val day: Long,
    val plays: Int,
    /** Real listening time where known, else the song's length. */
    val listenedMs: Long,
)

data class TopTrackRow(
    @Embedded val track: TrackEntity,
    val plays: Int,
    val lastPlayedAt: Long,
)

data class TopArtistRow(
    val artist: String,
    val plays: Int,
    /** Distinct songs by this artist heard in the range. */
    val songs: Int,
    val lastPlayedAt: Long,
    val albumArtUrl: String?,
    val albumArtPath: String?,
)

data class TopAlbumRow(
    val album: String,
    /** The album artist, or the song's artist when the album artist is unknown. */
    val artist: String,
    val plays: Int,
    val songs: Int,
    val lastPlayedAt: Long,
    val albumArtUrl: String?,
    val albumArtPath: String?,
)

data class ListeningTotals(
    val plays: Int,
    val songs: Int,
    val artists: Int,
    /** Days with at least one listen. */
    val days: Int,
    val listenedMs: Long,
    /** Listens whose time is the song's length because the real time is unknown. */
    val estimatedPlays: Int,
) {
    companion object {
        val EMPTY = ListeningTotals(0, 0, 0, 0, 0, 0)
    }
}

data class HourCount(val hour: Int, val plays: Int)

data class ObsessionRow(
    @Embedded val track: TrackEntity,
    val day: Long,
    val plays: Int,
)

data class UnstampedListen(
    val id: Long,
    val startedAt: Long,
)

data class EventStamp(
    val eventId: Long,
    val day: Long,
    val minute: Int,
)
