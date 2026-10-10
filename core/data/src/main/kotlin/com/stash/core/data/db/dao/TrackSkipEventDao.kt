package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.stash.core.data.db.chunkedForBind
import com.stash.core.data.db.entity.TrackSkipEventEntity

/** Per-track skip-rate projection for the recommender's negative weighting. */
data class TrackSkipStats(
    val trackId: Long,
    val skips: Int,
    val plays: Int,
)

@Dao
interface TrackSkipEventDao {

    @Insert
    suspend fun insert(event: TrackSkipEventEntity): Long

    @Query("SELECT COUNT(*) FROM track_skip_events WHERE track_id = :trackId AND skipped_at >= :sinceMs")
    suspend fun countSkipsSince(trackId: Long, sinceMs: Long): Int

    /**
     * Returns skip + play counts per track for the rolling window.
     * Used by [com.stash.core.data.mix.MixGenerator] to compute a
     * skip-rate penalty: tracks with high skip ratio over recent
     * encounters get demoted in scoring.
     */
    @Query(
        """
        SELECT t.id AS trackId,
               (SELECT COUNT(*) FROM track_skip_events s
                  WHERE s.track_id = t.id AND s.skipped_at >= :sinceMs) AS skips,
               (SELECT COUNT(*) FROM listening_events le
                  WHERE le.track_id = t.id AND le.started_at >= :sinceMs AND le.origin_device IS NULL) AS plays
        FROM tracks t
        WHERE t.id IN (:trackIds)
        """
    )
    suspend fun getSkipStatsSinceRaw(trackIds: List<Long>, sinceMs: Long): List<TrackSkipStats>

    /**
     * Chunked wrapper for [getSkipStatsSinceRaw]: MixGenerator passes the whole
     * downloaded library, so an unchunked IN() crashed mix scoring for
     * >999-track libraries (#337). One row per track, so chunks concatenate.
     */
    suspend fun getSkipStatsSince(trackIds: List<Long>, sinceMs: Long): List<TrackSkipStats> =
        trackIds.chunkedForBind { getSkipStatsSinceRaw(it, sinceMs) }

    /**
     * Returns canonical-key set ("$canonicalArtist|$canonicalTitle") for
     * tracks that have been "early-skipped" at least [minSkips] times since
     * [sinceMs], where "early" means within the first [maxPositionMs]
     * milliseconds of playback. Used by [com.stash.core.data.sync.workers.StashMixRefreshWorker]'s
     * discovery pre-filter to ban candidates the user has repeatedly
     * rejected.
     *
     * Position-aware: a skip 90% of the way through a song is "finished
     * listening, moving on," not a verdict. Only skips in the first
     * [maxPositionMs] count.
     *
     * Joins to `tracks` to look up the canonical key. Tracks deleted from
     * the library are naturally excluded (INNER JOIN) — acceptable; if a
     * track is gone we don't need to ban it from future re-discovery.
     */
    @Query(
        """
        SELECT (t.canonical_artist || '|' || t.canonical_title) AS k
        FROM track_skip_events s
        INNER JOIN tracks t ON t.id = s.track_id
        WHERE s.skipped_at >= :sinceMs
          AND s.position_ms <= :maxPositionMs
        GROUP BY k
        HAVING COUNT(*) >= :minSkips
        """
    )
    suspend fun getEarlySkipBannedCanonicalKeys(
        minSkips: Int,
        sinceMs: Long,
        maxPositionMs: Long,
    ): List<String>
}
