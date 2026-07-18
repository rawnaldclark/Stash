package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.ListeningEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the [ListeningEventEntity] table. Kept small: the features that
 * consume listening history (Last.fm scrobbler, Stash Mixes generator)
 * do their own joins against `tracks` in their own queries.
 */
@Dao
interface ListeningEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ListeningEventEntity): Long

    @Query(
        """
        UPDATE listening_events SET scrobbled = 1
        WHERE id = :eventId
        """
    )
    suspend fun markScrobbled(eventId: Long)

    /**
     * Unscrobbled events awaiting Last.fm submission, oldest first so the
     * scrobbler submits in chronological order. Last.fm accepts a batch of
     * up to 50 per request; callers chunk accordingly.
     */
    @Query(
        """
        SELECT * FROM listening_events
        WHERE scrobbled = 0
        ORDER BY started_at ASC
        LIMIT :limit
        """
    )
    suspend fun pendingScrobbles(limit: Int = 100): List<ListeningEventEntity>

    /** Count of unscrobbled events. Useful for Settings UI ("12 pending"). */
    @Query("SELECT COUNT(*) FROM listening_events WHERE scrobbled = 0")
    fun pendingScrobbleCount(): Flow<Int>

    /**
     * Unscrobbled-to-YT events awaiting submission to the YouTube Music
     * recommender graph. Same shape as [pendingScrobbles] but gated on
     * `yt_scrobbled` instead of `scrobbled`. Oldest first so submissions
     * chronologically mirror actual listening order.
     */
    @Query(
        """
        SELECT * FROM listening_events
        WHERE yt_scrobbled = 0
        ORDER BY started_at ASC
        LIMIT :limit
        """
    )
    suspend fun pendingYtScrobbles(limit: Int = 100): List<ListeningEventEntity>

    @Query(
        """
        UPDATE listening_events SET yt_scrobbled = 1
        WHERE id = :eventId
        """
    )
    suspend fun markYtScrobbled(eventId: Long)

    /**
     * v0.9.13: Mark a listening event as having crossed the completion
     * threshold. Called by [ListeningRecorder] at insert time.
     */
    @Query("UPDATE listening_events SET completed_at = :ts WHERE id = :eventId")
    suspend fun markCompleted(eventId: Long, ts: Long)

    /**
     * v0.9.13: Count how many DISTINCT calendar days (UTC) within the
     * window have a completed listen for this track. Used by the
     * AutoSaveScrobbler threshold check. SQLite-native — no app-side
     * aggregation. Typical execution time: 1-2ms.
     *
     * `date(.., 'unixepoch')` converts epoch-millis to YYYY-MM-DD.
     * COUNT(DISTINCT date) gives unique-day count.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT date(completed_at / 1000, 'unixepoch'))
        FROM listening_events
        WHERE track_id = :trackId
          AND completed_at IS NOT NULL
          AND completed_at > :sinceMs
        """
    )
    suspend fun distinctDaysCompletedFor(trackId: Long, sinceMs: Long): Int

    /**
     * v0.9.13: Reactive most-recent completion. Used by the
     * AutoSaveScrobbler to wake up when new completions land — same
     * trigger pattern as LastFmScrobbler's `pendingScrobbleCount()`
     * Flow. Returns null if no completion ever recorded.
     */
    @Query("SELECT MAX(completed_at) FROM listening_events WHERE completed_at IS NOT NULL")
    fun observeMostRecentCompletion(): Flow<Long?>

    /**
     * v0.9.13: Lookup helper for AutoSaveScrobbler. The
     * observeMostRecentCompletion Flow emits a timestamp, not a row
     * id; we resolve to the row to read `track_id`. Returns null if
     * the matching row was deleted between Flow emit and this query
     * (rare — orphan-cleanup running concurrently).
     */
    @Query("""
        SELECT * FROM listening_events
        WHERE completed_at = :completedAtMs
        ORDER BY id DESC LIMIT 1
    """)
    suspend fun findByCompletedAt(completedAtMs: Long): ListeningEventEntity?

    /** Count of unscrobbled-to-YT events. Drives the Settings health badge. */
    @Query("SELECT COUNT(*) FROM listening_events WHERE yt_scrobbled = 0")
    fun pendingYtScrobbleCount(): Flow<Int>

    /**
     * Per-track play counts in a recency window, used by the (future)
     * Stash Mixes engine. Window is expressed as a cutoff epoch millis;
     * callers pass `now - 30 days` or similar.
     */
    @Query(
        """
        SELECT track_id AS trackId, COUNT(*) AS plays
        FROM listening_events
        WHERE started_at >= :sinceEpochMs
        GROUP BY track_id
        ORDER BY plays DESC
        LIMIT :limit
        """
    )
    suspend fun topTracksSince(sinceEpochMs: Long, limit: Int = 50): List<TrackPlayCount>

    data class TrackPlayCount(
        val trackId: Long,
        val plays: Int,
    )

    /**
     * All play counts for tracks listened to since [sinceEpochMs]. Used by
     * [com.stash.core.data.mix.MixGenerator] to build affinity scores. No
     * limit — callers need the whole library, not just the top N.
     */
    @Query(
        """
        SELECT track_id AS trackId, COUNT(*) AS plays
        FROM listening_events
        WHERE started_at >= :sinceEpochMs
        GROUP BY track_id
        """
    )
    suspend fun getPlayCountsSince(sinceEpochMs: Long): List<TrackPlayCount>

    /**
     * v0.9.16: Per-track plays + most-recent-play timestamp for the
     * recency-decayed affinity term in [com.stash.core.data.mix.MixGenerator].
     */
    data class TrackPlayCountWithLatest(
        val trackId: Long,
        val plays: Int,
        val latestPlayedAt: Long,
    )

    /**
     * v0.9.16: Listening-event total + completed-listen count over a
     * window. Used by the recommender's completion-rate scoring term:
     * tracks with high completion vs. starts get an affinity boost.
     */
    data class CompletionStats(
        val trackId: Long,
        val total: Int,
        val completed: Int,
    )

    @Query(
        """
        SELECT track_id AS trackId, COUNT(*) AS plays, MAX(started_at) AS latestPlayedAt
        FROM listening_events
        WHERE started_at >= :sinceEpochMs
        GROUP BY track_id
        """
    )
    suspend fun getPlayCountsSinceWithLatest(sinceEpochMs: Long): List<TrackPlayCountWithLatest>

    @Query(
        """
        SELECT track_id AS trackId,
               COUNT(*) AS total,
               SUM(CASE WHEN completed_at IS NOT NULL THEN 1 ELSE 0 END) AS completed
        FROM listening_events
        WHERE track_id IN (:trackIds) AND started_at >= :sinceMs
        GROUP BY track_id
        """
    )
    suspend fun getCompletionStatsSince(trackIds: List<Long>, sinceMs: Long): List<CompletionStats>

    /**
     * Track IDs played at least once since [sinceEpochMs]. Used for the
     * freshness-window filter — mix recipes exclude tracks in this set
     * so a Rediscovery mix never surfaces last week's plays.
     */
    @Query(
        """
        SELECT DISTINCT track_id FROM listening_events
        WHERE started_at >= :sinceEpochMs
        """
    )
    suspend fun getTrackIdsPlayedSince(sinceEpochMs: Long): List<Long>

    /**
     * Top artists by play count in the window. Seeds the Last.fm
     * similar-artist discovery pipeline — the top N artists per-recipe
     * drive what new tracks we go hunt for.
     */
    @Query(
        """
        SELECT t.artist AS artist, COUNT(*) AS plays
        FROM listening_events le
        INNER JOIN tracks t ON t.id = le.track_id
        WHERE le.started_at >= :sinceEpochMs
        GROUP BY LOWER(t.artist)
        ORDER BY plays DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtistsSince(sinceEpochMs: Long, limit: Int = 20): List<ArtistPlayCount>

    /**
     * Which of [trackIds] the user has ever played. Lets the mix
     * survivor rotation serve unheard discoveries before repeats.
     */
    @Query("SELECT DISTINCT track_id FROM listening_events WHERE track_id IN (:trackIds)")
    suspend fun getPlayedTrackIdsAmong(trackIds: List<Long>): List<Long>

    data class ArtistPlayCount(val artist: String, val plays: Int)

    /**
     * Top tracks by in-app play count within the time window, returned as
     * (artist, title) pairs ready for [com.stash.core.data.mix.MixSeedGenerator]'s
     * TRACK_SIMILAR seed source. Used as the second tier of the seedTracks
     * fallback chain (Last.fm persona top tracks → local listening events →
     * library top by LFM playcount).
     */
    @Query(
        """
        SELECT t.artist AS artist, t.title AS title
        FROM listening_events e
        INNER JOIN tracks t ON t.id = e.track_id
        WHERE e.started_at >= :sinceEpochMs
        GROUP BY e.track_id
        ORDER BY COUNT(*) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopTracksByLocalPlays(sinceEpochMs: Long, limit: Int): List<TrackArtistTitle>
}
