package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.TrackTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the `track_tags` join table that backs Stash Mix recipe
 * filtering. Read side: look up tracks by tag or tags by track. Write
 * side: idempotent upsert after each Last.fm enrichment call + resumable
 * batching queries for the [com.stash.core.data.sync.workers.TagEnrichmentWorker].
 */
@Dao
interface TrackTagDao {

    /** Upsert a single tag row. PK is (track_id, tag) so replays are safe. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TrackTagEntity)

    /**
     * Replace every tag row for a track in a single transaction. Called
     * by [TagEnrichmentWorker] after fetching fresh data so a track that
     * had track-level tags (and those tags changed on Last.fm) doesn't
     * carry stale entries.
     */
    @Transaction
    suspend fun replaceForTrack(trackId: Long, tags: List<TrackTagEntity>) {
        deleteForTrack(trackId)
        tags.forEach { upsert(it) }
    }

    @Query("DELETE FROM track_tags WHERE track_id = :trackId")
    suspend fun deleteForTrack(trackId: Long)

    /** All tags for a given track (used by MixGenerator's tag-match score). */
    @Query("SELECT * FROM track_tags WHERE track_id = :trackId")
    suspend fun getByTrack(trackId: Long): List<TrackTagEntity>

    /**
     * Tracks whose title+artist canonical identity matches any of the
     * supplied tags, ordered by the *sum of weights* per track. Used by
     * MixGenerator when the recipe has a non-empty include-tag set.
     */
    @Query(
        """
        SELECT track_id
        FROM track_tags
        WHERE tag IN (:tags)
        GROUP BY track_id
        ORDER BY SUM(weight) DESC
        """
    )
    suspend fun getTrackIdsByTags(tags: List<String>): List<Long>

    /**
     * Track IDs tagged with any of [tags] — used for the `excludeTags`
     * hard filter. Returns the track_ids directly so the caller can
     * subtract from its candidate set.
     */
    @Query("SELECT DISTINCT track_id FROM track_tags WHERE tag IN (:tags)")
    suspend fun getTrackIdsMatchingAny(tags: List<String>): List<Long>

    /**
     * Library tracks that have never been tag-enriched. Feeds the
     * resumable batch loop inside [TagEnrichmentWorker]. Limiting here
     * rather than in the worker keeps the SQL simple and lets Room
     * optimize the query plan. Only downloaded tracks — tagging tracks
     * that are never going to be playable wastes rate limit.
     */
    @Query(
        """
        SELECT t.id FROM tracks t
        WHERE t.is_downloaded = 1
          AND t.is_blacklisted = 0
          AND t.id NOT IN (SELECT DISTINCT track_id FROM track_tags)
        ORDER BY t.date_added DESC
        LIMIT :limit
        """
    )
    suspend fun findUntaggedDownloadedTrackIds(limit: Int): List<Long>

    /** Count of tracks that still need enrichment — drives a diagnostics badge. */
    @Query(
        """
        SELECT COUNT(*) FROM tracks t
        WHERE t.is_downloaded = 1
          AND t.is_blacklisted = 0
          AND t.id NOT IN (SELECT DISTINCT track_id FROM track_tags)
        """
    )
    fun observeUntaggedCount(): Flow<Int>

    /** All tags currently in the index, with the number of tracks each applies to. */
    @Query(
        """
        SELECT tag, COUNT(DISTINCT track_id) AS track_count
        FROM track_tags
        GROUP BY tag
        ORDER BY track_count DESC
        """
    )
    suspend fun getTagHistogram(): List<TagCount>

    /** Projection for the tag histogram. */
    data class TagCount(val tag: String, @androidx.room.ColumnInfo(name = "track_count") val trackCount: Int)
}
