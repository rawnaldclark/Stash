package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import kotlinx.coroutines.flow.Flow

/** Queue DAO for Stash Mix discovery candidates. */
@Dao
interface DiscoveryQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(entry: DiscoveryQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfNew(entries: List<DiscoveryQueueEntity>)

    /** Pending entries for the discovery worker to drain. */
    @Query(
        """
        SELECT * FROM discovery_queue
        WHERE status = 'PENDING'
        ORDER BY queued_at ASC
        LIMIT :limit
        """
    )
    suspend fun getPending(limit: Int): List<DiscoveryQueueEntity>

    /**
     * Pending entries that aren't in the supplied at-cap recipe set.
     * Without this fairness filter, a single recipe's deferred-at-cap
     * backlog (rows that keep getting skipped because their recipe is
     * over its weekly download budget) sits at the head of the queue and
     * starves out fresher candidates from under-cap recipes. See
     * conversation 2026-05-12: First Listen had 100+ deferred PENDING
     * blocking Deep Cuts' freshly-queued candidates from ever being
     * processed.
     *
     * Pass an empty list to behave like [getPending] — Room rejects
     * empty `IN ()` SQL so the worker special-cases the empty case
     * before calling.
     */
    @Query(
        """
        SELECT * FROM discovery_queue
        WHERE status = 'PENDING'
          AND recipe_id NOT IN (:cappedRecipeIds)
        ORDER BY queued_at ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingExcludingRecipes(
        cappedRecipeIds: List<Long>,
        limit: Int,
    ): List<DiscoveryQueueEntity>

    /**
     * Distinct recipe ids that currently have at least one PENDING row.
     * Feeds the round-robin scheduler in [com.stash.core.data.sync.workers.StashDiscoveryWorker]:
     * we ask which recipes are waiting for work, then pull a fair quota
     * from each rather than letting one recipe's FIFO backlog monopolise
     * the batch (conversation 2026-05-28).
     */
    @Query(
        """
        SELECT DISTINCT recipe_id FROM discovery_queue
        WHERE status = 'PENDING'
          AND recipe_id NOT IN (:cappedRecipeIds)
        """
    )
    suspend fun getRecipesWithPending(cappedRecipeIds: List<Long>): List<Long>

    /**
     * PENDING rows for a single recipe, NEWEST first. Used by the
     * round-robin scheduler to pull a bounded per-recipe quota out of the
     * queue so no one recipe can starve the others — v0.9.38 fairness fix.
     *
     * #287: was oldest-first (FIFO). With a deep backlog that meant the
     * daily completion budget got spent on candidates from weeks-old
     * seeds while TODAY's recent-listening candidates waited days at the
     * back of the line — observed on device as Motown-era completions
     * while fresh Panda Bear/Dan Deacon candidates sat pending. Newest
     * seeds complete first; the stale tail drains with leftover budget
     * and ages out via the 30-day PENDING TTL.
     */
    @Query(
        """
        SELECT * FROM discovery_queue
        WHERE status = 'PENDING'
          AND recipe_id = :recipeId
        ORDER BY queued_at DESC
        LIMIT :limit
        """
    )
    suspend fun getPendingForRecipe(recipeId: Long, limit: Int): List<DiscoveryQueueEntity>

    /**
     * Recipe ids currently over the weekly cap. Mirrors
     * [countRecentCompletedForRecipe]'s join + filter exactly so the
     * pre-fetch and per-row checks agree.
     *
     * v0.9.38: counts both `is_downloaded = 1` AND `is_streamable = 1`
     * rows. The v0.9.37 stream-only seam stopped writing
     * `is_downloaded = 1` for discovery survivors — they land as
     * streamable stubs only. The old downloaded-only count returned 0
     * for every recipe and the cap never engaged, so a single recipe's
     * FIFO PENDING backlog could monopolise the worker indefinitely
     * (Daily Discover drained 60/day while Deep Cuts + First Listen
     * stayed at 0 DONE — conversation 2026-05-28).
     */
    @Query(
        """
        SELECT dq.recipe_id FROM discovery_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.status = 'DONE'
          AND dq.completed_at >= :sinceMillis
          AND (t.is_downloaded = 1 OR t.is_streamable = 1)
        GROUP BY dq.recipe_id
        HAVING COUNT(*) >= :cap
        """
    )
    suspend fun findRecipesAtWeeklyCap(sinceMillis: Long, cap: Int): List<Long>

    /**
     * Count of discovery entries completed within [sinceMillis] for a
     * specific recipe — enforces the per-recipe weekly cap so a single
     * recipe doesn't monopolise the queue drainer.
     *
     * v0.9.21 narrowed this to `is_downloaded = 1` so unmaterialised stubs
     * wouldn't "trap" a recipe at cap with 0 actually-downloaded tracks.
     * v0.9.37 inverted that situation: the stream-only seam stopped writing
     * downloads at all for discoveries, so this query returned 0 for every
     * recipe and the cap stopped engaging — letting one recipe's FIFO
     * PENDING backlog starve the others (conversation 2026-05-28).
     *
     * v0.9.38: count both `is_downloaded = 1` and `is_streamable = 1`. The
     * cap now bounds real worker-output volume per recipe regardless of
     * which delivery mode wins, which is the property the cap was always
     * meant to express. Mirror [findRecipesAtWeeklyCap] exactly.
     */
    @Query(
        """
        SELECT COUNT(*) FROM discovery_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.recipe_id = :recipeId
          AND dq.status = 'DONE'
          AND dq.completed_at >= :sinceMillis
          AND (t.is_downloaded = 1 OR t.is_streamable = 1)
        """
    )
    suspend fun countRecentCompletedForRecipe(recipeId: Long, sinceMillis: Long): Int

    @Query(
        """
        UPDATE discovery_queue
        SET status = :status, track_id = :trackId,
            completed_at = :completedAt, error_message = :errorMessage
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        trackId: Long? = null,
        completedAt: Long? = null,
        errorMessage: String? = null,
    )

    /** Diagnostics projection for the Settings/debug view. */
    @Query(
        """
        SELECT status, COUNT(*) AS n FROM discovery_queue GROUP BY status
        """
    )
    fun observeStatusCounts(): Flow<List<StatusCount>>

    data class StatusCount(val status: String, val n: Int)

    /**
     * Per-recipe count of discovery rows that are NOT terminally failed —
     * i.e. PENDING/SEARCHED/DOWNLOADING (still in flight) or DONE (resolved,
     * pending link into the mix on the next materialize). Used to tell a
     * still-building custom mix (count > 0) from one whose discovery finished
     * with nothing (count == 0). Reactive so the "Building…" indicator clears
     * itself as rows drain or tracks land.
     */
    @Query(
        """
        SELECT recipe_id AS recipeId, COUNT(*) AS count
        FROM discovery_queue
        WHERE status != 'FAILED'
        GROUP BY recipe_id
        """
    )
    fun observeNonFailedCountsByRecipe(): Flow<List<RecipeDiscoveryCount>>

    data class RecipeDiscoveryCount(val recipeId: Long, val count: Int)

    /**
     * Track ids for every DONE discovery row whose track still exists
     * in the tracks table. Used by [StashMixRefreshWorker.materializeMix]
     * to re-link surviving discovery tracks after the refresh clears the
     * playlist — without this step, a Discovery download that completed
     * between refreshes gets wiped from the mix and then garbage-
     * collected by the orphan sweeper.
     *
     * v0.9.19 follow-up: capped at [limit] rows ordered by
     * `completed_at DESC`, so newest-DONE survivors win when
     * `materializeMix` trims to the recipe's discovery slot count
     * (`targetLength * discoveryRatio`). Without the cap, every DONE row
     * accumulated for the recipe got re-linked, growing the playlist
     * unboundedly across release transitions (Daily Discover went
     * 50 -> 100 between v0.9.18 and v0.9.19 because ~50 survivors had
     * accumulated). SQLite treats NULL as "smaller than any value" under
     * `DESC`, so any pathological NULL `completed_at` rows fall to the
     * end naturally, but they're already excluded by the status filter
     * since worker DONE transitions stamp the timestamp.
     *
     * v0.9.20 follow-up: INNER JOIN tracks filters out orphan rows whose
     * track_id no longer points at a live row in `tracks` (deleted by the
     * orphan sweeper, user delete, or stale state from migrations). Without
     * this JOIN, materializeMix's insertCrossRef into playlist_tracks throws
     * SQLITE_CONSTRAINT_FOREIGNKEY because the FK on tracks(id) is enforced.
     *
     * v0.9.20 follow-up: AND t.is_downloaded = 1 ensures the mix never
     * surfaces a stub TrackEntity (a discovery row that was marked DONE
     * by StashDiscoveryWorker but whose file hasn't landed on disk yet).
     * Without this filter, a transient window between StashDiscoveryWorker
     * completion and DiscoveryDownloadWorker completion would put unplayable
     * tracks in the playlist. DiscoveryDownloadWorker fixes the underlying
     * issue (downloads run promptly); this filter is defense-in-depth.
     */
    @Query(
        """
        SELECT dq.track_id FROM discovery_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.recipe_id = :recipeId
          AND dq.status = 'DONE'
          AND dq.track_id IS NOT NULL
          AND t.is_downloaded = 1
        ORDER BY dq.completed_at DESC
        LIMIT :limit
        """
    )
    suspend fun getDoneTrackIdsForRecipe(recipeId: Long, limit: Int): List<Long>

    /**
     * Track ids referenced by any non-terminal discovery row (PENDING /
     * DONE). Fed to the orphan sweeper so in-flight + just-completed
     * discovery tracks don't get deleted between refresh and re-link.
     */
    @Query(
        """
        SELECT DISTINCT track_id FROM discovery_queue
        WHERE track_id IS NOT NULL
          AND status IN ('PENDING', 'DONE')
        """
    )
    suspend fun getActiveTrackIds(): List<Long>

    /**
     * Does this recipe already have an entry for (artist, title) queued
     * within the TTL window? v0.9.16 introduces a 30-day TTL on the
     * dedup key — older candidates that failed download or were
     * skipped/blocked previously can re-enter the funnel after a month
     * so the discovery surface stays fresh instead of permanently
     * pinning a candidate the moment it's first queued.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM discovery_queue
            WHERE recipe_id = :recipeId
              AND LOWER(artist) = LOWER(:artist)
              AND LOWER(title) = LOWER(:title)
              AND queued_at >= :sinceMs
        )
        """
    )
    suspend fun existsForRecipeSince(
        recipeId: Long,
        artist: String,
        title: String,
        sinceMs: Long,
    ): Boolean

    /**
     * Age-out pass: delete PENDING rows that have been queued longer than
     * [cutoffMillis] ago (typically now − 30 days). Stale pending
     * candidates clog the queue's drain order without contributing value
     * — the user's taste has usually moved on, and newer refreshes will
     * surface any still-relevant similar-artist suggestions anyway.
     * Completed (DONE) / failed rows are left alone so the per-recipe
     * weekly-cap query and the re-link step still see accurate history.
     *
     * Returns the number of rows deleted — non-zero values are logged by
     * [StashDiscoveryWorker] as a diagnostic signal.
     */
    @Query(
        """
        DELETE FROM discovery_queue
        WHERE status = 'PENDING'
          AND queued_at < :cutoffMillis
        """
    )
    suspend fun deleteStalePending(cutoffMillis: Long): Int

    /**
     * One-time PR 7 cleanup: delete discovery_queue DONE rows whose linked
     * track has `source != 'YOUTUBE'`. Such rows were created pre-PR-6 by
     * StashDiscoveryWorker.handle()'s canonical-dedup branch — Last.fm
     * candidates that matched an existing library track got linked to that
     * track instead of producing a real discovery stub. They surface in
     * mixes as "discovery survivors" despite being library content.
     *
     * Heuristic: real discovery stubs have `source = MusicSource.YOUTUBE`
     * (set by StashDiscoveryWorker.handle's stub-creation branch). Library
     * tracks have other sources (`SPOTIFY`, `LOCAL`, `BOTH`).
     *
     * PR 6's seed-stage pre-filter prevents new library-hit rows from being
     * created; this query cleans up the accumulated backlog.
     *
     * @return number of rows deleted.
     */
    @Query(
        """
        DELETE FROM discovery_queue
        WHERE status = 'DONE'
          AND track_id IN (SELECT id FROM tracks WHERE source != 'YOUTUBE')
        """
    )
    suspend fun deleteLibraryHitDoneRows(): Int
}
