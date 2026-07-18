package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmPeriod
import com.stash.core.data.lastfm.LastFmPersonas
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.lastfm.LastFmTopArtist
import com.stash.core.data.lastfm.LastFmTopTrack
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.MixSeedStrategy
import com.stash.core.data.mix.RecipeTagResolver
import com.stash.core.data.mix.StashMixDefaults
import com.stash.core.data.mix.TagPoolBuilder
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Periodic worker that regenerates every active Stash Mix. For each
 * recipe:
 *
 *  1. Run [MixGenerator.generate] — pure-Kotlin ranking over the library.
 *  2. Find-or-create the backing [PlaylistEntity]. First refresh creates
 *     a new row with `type = STASH_MIX` and stores its id back on the
 *     recipe. Subsequent refreshes replace the track list in place so
 *     the Home-screen card URL and the user's history pointers stay
 *     stable.
 *  3. Rewrite [PlaylistTrackCrossRef] rows (REFRESH semantics).
 *  4. Recompute the cover art tiles from the top tracks.
 *  5. Stamp `last_refreshed_at`.
 *  6. For recipes with non-zero discovery ratio: query Last.fm
 *     `artist.getSimilar` seeded from the user's current top artists in
 *     that recipe's tag space, and enqueue candidate tracks into
 *     [com.stash.core.data.db.dao.DiscoveryQueueDao]. The actual search
 *     + download happens in a separate worker.
 *
 * Runs once per day (via WorkManager periodic scheduling) with no network
 * constraint — mix generation is purely local. The discovery Last.fm
 * queries inside run in a best-effort runCatching so a network outage
 * never blocks refreshing.
 */
@HiltWorker
class StashMixRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recipeDao: StashMixRecipeDao,
    private val playlistDao: PlaylistDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val mixGenerator: MixGenerator,
    private val seedGenerator: MixSeedGenerator,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmCredentials: LastFmCredentials,
    private val sessionPreference: LastFmSessionPreference,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val trackSkipEventDao: TrackSkipEventDao,
    private val tagPoolBuilder: TagPoolBuilder,
    private val trackMatcher: TrackMatcher,
    private val downloadNetworkPreference: DownloadNetworkPreference,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "StashMixRefresh"
        private const val WORK_NAME = "stash_mix_refresh"
        const val ONE_SHOT_WORK_NAME = "stash_mix_refresh_oneshot"
        private const val TOP_ARTISTS_LIMIT = 8
        private const val SIMILAR_REQUEST_INTERVAL_MS = 220L
        private const val AFFINITY_LOOKBACK_DAYS = 180L

        /**
         * Floor for the filtered discovery pool. Below this, the TAG_GRAPH
         * fallback fires.
         *
         * Why 20 and not the recipe's discoveryCap (e.g. 34 for an 85%-discovery
         * recipe with targetLength 40)? Downstream attrition: not every queued
         * candidate becomes a viable survivor. StashDiscoveryWorker.handle()
         * applies an additional blocklist check; some candidates fail at the
         * download stage (yt-dlp can't match, no audio available, etc.); and
         * some get canonical-deduped against tracks added to the library AFTER
         * the seed-gen filter ran. Twenty pre-filter survivors typically yields
         * ~10-15 actually-downloaded DONE rows — enough to fill a 6-slot mix
         * slot AND leave a queue buffer for tomorrow's refresh. The fallback
         * is a viability gate, not a precise capacity match.
         */
        private const val MIN_DISCOVERY_POOL_AFTER_FILTER = 20

        /** Skip-event ban threshold: this many "early" skips in the time window → ban. */
        private const val DISCOVERY_SKIP_BAN_MIN_COUNT = 3

        /** Skip-event ban time window in milliseconds. */
        private val DISCOVERY_SKIP_BAN_WINDOW_MS = TimeUnit.DAYS.toMillis(90)

        /** Skip-position cutoff: skips in the first this-many ms count as rejection. */
        private const val DISCOVERY_SKIP_BAN_MAX_POSITION_MS = 30_000L

        /** Timeout for the TAG_GRAPH fallback Last.fm call. */
        private const val SEED_FALLBACK_TIMEOUT_MS = 30_000L

        // ── #287: day-fresh Daily Discover ────────────────────────────────
        /**
         * "Yesterday's listening" window that leads the seed chain. Two days,
         * not one — a calendar-day boundary would empty the window every
         * morning before the user plays anything.
         */
        private val RECENT_LISTENING_LOOKBACK_MS = TimeUnit.HOURS.toMillis(48)

        /** Guaranteed newest slice of the rotated survivor window. */
        private const val FRESH_HEAD_RATIO = 0.3f

        /** Upper bound on the DONE backlog considered for rotation. */
        private const val SURVIVOR_POOL_LIMIT = 500

        private val DAY_MS = TimeUnit.DAYS.toMillis(1)

        /**
         * #287: rotate the discovery-survivor window across the recipe's whole
         * accepted backlog instead of pinning the newest [cap] forever — the
         * old newest-first `.take(cap)` meant a refresh with no new DONE rows
         * recomputed the identical list and the idempotency backstop skipped
         * the write, so "Refresh" visibly did nothing.
         *
         * [pool] arrives newest-first. The newest ~[FRESH_HEAD_RATIO] of the
         * window is always kept (new finds must surface); the rest is a
         * seeded shuffle of the remaining backlog. Same [seed] → same window:
         * manual refreshes pass a per-tap seed (every tap rotates), periodic
         * runs pass a day bucket (rotates daily, stable within the day so
         * same-day re-runs still no-op).
         */
        internal fun rotateSurvivorWindow(pool: List<Long>, cap: Int, seed: Long): List<Long> {
            if (cap <= 0) return emptyList()
            if (pool.size <= cap) return pool
            val freshHead = (cap * FRESH_HEAD_RATIO).roundToInt().coerceIn(0, cap)
            val head = pool.take(freshHead)
            val rest = pool.drop(freshHead).shuffled(kotlin.random.Random(seed))
            return head + rest.take(cap - head.size)
        }

        /**
         * #287: recent listening leads, older taste backfills — dedup'd,
         * capped at [limit]. Keeps discovery pointed at what the user played
         * yesterday without collapsing to a single-artist echo chamber when
         * the recent window is thin.
         */
        internal fun <T> blendRecentFirst(recent: List<T>, fallback: List<T>, limit: Int): List<T> =
            (recent + fallback.filterNot { it in recent }).take(limit)

        /**
         * Input-data key for [enqueueOneTime] (single-recipe overload).
         * When present and > 0, [doWork] only refreshes that one recipe
         * instead of iterating every active builtin. Backs the manual
         * "Refresh this mix" action surfaced from the Home long-press
         * menu.
         */
        const val KEY_RECIPE_ID = "stash_mix_refresh_recipe_id"

        /**
         * Input-data key for [enqueueOneTime]'s materialize-only overload.
         * When true, [doWork] LINKS already-drained discovery stubs into the
         * mix playlists but does NOT re-queue discovery candidates and does
         * NOT re-kick the discovery drain. This is the post-drain re-link
         * path ([com.stash.core.data.sync.workers.StashDiscoveryWorker] fires
         * it when it materializes a stub) — running it in full mode formed a
         * runaway refresh⇄drain loop that continuously cleared + reinserted
         * every mix (the user-visible "load 40 tracks, then repopulate with
         * different tracks" churn on multi-genre mixes).
         */
        const val KEY_MATERIALIZE_ONLY = "stash_mix_refresh_materialize_only"

        /**
         * Schedule the periodic refresh. Default 24-hour cadence with no
         * constraints — the library-only path works offline and fast
         * enough to not care. Discovery is opportunistic and tolerates
         * being skipped when the device is offline.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val work = PeriodicWorkRequestBuilder<StashMixRefreshWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(constraints)
                .build()
            // 2026-05-11: UPDATE (not KEEP) so existing installs reschedule against
            // the current worker spec on the next cold start. KEEP previously meant
            // constraint changes / class changes were ignored across upgrades —
            // a credible cause of "periodic refresh hasn't fired in 3 days" reports.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        }

        /**
         * Fire a one-shot refresh immediately — used on first app launch
         * after seeding defaults so users see populated mixes without
         * waiting 24 hours for the periodic schedule, and by the
         * manual-refresh button on the Home Stash Mixes card.
         */
        fun enqueueOneTime(context: Context, materializeOnly: Boolean = false) {
            val builder = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
            if (materializeOnly) {
                builder.setInputData(androidx.work.workDataOf(KEY_MATERIALIZE_ONLY to true))
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                builder.build(),
            )
        }

        /**
         * Fire a one-shot refresh for a single recipe. Used by the Home
         * long-press "Refresh this mix" action. Distinct unique-work name
         * per recipe id so refreshing mix A doesn't clobber a still-pending
         * refresh of mix B; REPLACE means a rapid double-tap on the same
         * mix coalesces into one job.
         */
        fun enqueueOneTime(context: Context, recipeId: Long) {
            val data = androidx.work.workDataOf(KEY_RECIPE_ID to recipeId)
            val work = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${ONE_SHOT_WORK_NAME}_$recipeId",
                androidx.work.ExistingWorkPolicy.REPLACE,
                work,
            )
        }
    }

    override suspend fun doWork(): Result {
        // Safety net: make sure default recipes exist. Normally seeded at
        // app startup; running here too means a fresh-install user gets
        // their first mixes even if the startup hook is racy with the
        // first WorkManager tick.
        StashMixDefaults.seedIfNeeded(recipeDao)

        val materializeOnly = inputData.getBoolean(KEY_MATERIALIZE_ONLY, false)
        val targetId = inputData.getLong(KEY_RECIPE_ID, -1L)
        val active = if (targetId > 0L) {
            val one = recipeDao.getById(targetId)?.takeIf { it.isActive }
            if (one == null) {
                Log.d(TAG, "single-recipe refresh: recipe $targetId not found or inactive")
                return Result.success()
            }
            listOf(one)
        } else {
            recipeDao.getActive()
        }
        if (active.isEmpty()) {
            Log.d(TAG, "no active recipes")
            return Result.success()
        }
        Log.i(
            TAG,
            "refreshing ${active.size} Stash Mix(es)" +
                if (targetId > 0L) " (single: ${active.first().name})" else "",
        )
        // v0.9.21: diagnostic snapshot of every active recipe so we can rule
        // out duplicate-recipe / mismatched-id pathologies when a mix
        // mysteriously fails to link survivors.
        recipeDao.getActive().forEach { r ->
            Log.i(
                TAG,
                "  active recipe: id=${r.id} name='${r.name}' " +
                    "playlistId=${r.playlistId} ratio=${r.discoveryRatio} " +
                    "target=${r.targetLength} seed=${r.seedStrategy}",
            )
        }

        val now = System.currentTimeMillis()
        val lastFmConfigured = lastFmCredentials.isConfigured

        val username = sessionPreference.session.first()?.username
        val personas = if (lastFmConfigured && !username.isNullOrBlank()) {
            // v0.9.16: Bound the persona fetch — 5 periods × 2 endpoints =
            // 10 sequential HTTP calls. With a slow connection or upstream
            // hiccup, OkHttp's default timeouts could stack into multiple
            // minutes and blow past WorkManager's 10-min budget. 30s ceiling
            // means we degrade gracefully to library-only seeding when
            // Last.fm is sluggish; the next refresh tries again.
            runCatching {
                withTimeout(30_000L) { fetchPersonas(username) }
            }.getOrElse { e ->
                Log.w(TAG, "persona fetch failed/timed-out, falling back to local seeds", e)
                LastFmPersonas.EMPTY
            }
        } else LastFmPersonas.EMPTY

        // v0.9.20: pre-sort by dedup priority so the most-restrictive
        // recipes claim their natural picks before more-permissive ones
        // see them. excludeIds accumulates library + discovery ids
        // across iterations and is threaded into both generate() and
        // materializeMix() — guarantees no track appears in two
        // playlists in a single refresh run.
        val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }
        val excludeIds = mutableSetOf<Long>()

        // #287: manual single-recipe refresh rotates on every tap; periodic
        // batch runs rotate once per day (stable within the day so the
        // idempotency backstop still suppresses same-day no-op flashes).
        val rotationSeed = if (targetId > 0L) now else now / DAY_MS

        // v0.9.20 follow-up: single-recipe path needs explicit seeding from the
        // OTHER mixes' current playlist contents. The batch-mode loop accumulates
        // excludeIds naturally as it iterates; the single-element loop has nothing
        // to accumulate, so we seed it manually from the materialized state of the
        // other builtin mixes. Effect: manual refresh of one mix no longer overlaps
        // with whatever is currently in the others.
        if (targetId > 0L) {
            val otherPlaylistIds = recipeDao.getActive()
                .filter { it.id != targetId && it.playlistId != null }
                .mapNotNull { it.playlistId }
            if (otherPlaylistIds.isNotEmpty()) {
                excludeIds += playlistDao.getTrackIdsForPlaylists(otherPlaylistIds)
            }
        }

        for (recipe in orderedRecipes) {
            // Snapshot to an immutable Set per iteration so callees can't
            // observe (or accidentally mutate) the accumulator, and so
            // tests / future tracing can see exactly what was excluded
            // at the moment of the call.
            val excludeSnapshot = excludeIds.toSet()
            val tracks = mixGenerator.generate(recipe, excludeSnapshot)

            // Empty-tracks skip is only safe for library-only recipes
            // (discoveryRatio == 0). Pure-discovery recipes like "Stash
            // Discover" (ratio = 1.0) produce an empty generator result
            // by design — the playlist gets its content from the
            // discovery re-link pass inside materializeMix + the async
            // StashDiscoveryWorker. Skipping them here would keep stale
            // pre-retune content in the playlist forever.
            if (tracks.isEmpty() && recipe.discoveryRatio == 0f) {
                Log.d(TAG, "'${recipe.name}' produced 0 tracks and has no discovery — skipping materialize")
                continue
            }

            val result = materializeMix(recipe, tracks, now, excludeSnapshot, rotationSeed)
            recipeDao.setPlaylistId(recipe.id, result.playlistId)
            recipeDao.setLastRefreshedAt(recipe.id, now)

            // v0.9.20: accumulate so the next recipe in the ordering doesn't re-pick these.
            excludeIds += tracks.map { it.id }
            excludeIds += result.discoveryIds

            // Discovery — opportunistic. Don't block refresh success on it.
            // Skipped in materialize-only mode: the post-drain re-link must not
            // queue MORE candidates (that's the loop's fuel).
            if (recipe.discoveryRatio > 0f && lastFmConfigured && !materializeOnly) {
                runCatching { queueDiscoveryForRecipe(recipe, personas) }
                    .onFailure { Log.w(TAG, "discovery queueing failed for '${recipe.name}'", it) }
            }
        }

        // v0.9.38 — hydrate fresh stream-only stubs with art + duration.
        // StashDiscoveryWorker lands those rows bare; without this chain
        // they'd render empty in the mix until the player resolves a
        // queue window at play time (conversation 2026-05-28). Fire-and-
        // forget — backfill failure must never block a successful refresh,
        // so the enqueue itself is guarded (e.g. WorkManager not yet
        // initialised in a unit-test JVM throws IllegalStateException).
        runCatching { ArtBackfillWorker.enqueueFromMixRefresh(applicationContext) }
            .onFailure { Log.w(TAG, "art-backfill enqueue failed; refresh still succeeded", it) }

        // Drain the discovery_queue we just FILLED. queueDiscoveryForRecipe
        // above only enqueues PENDING candidate rows — it does not turn them
        // into stream-only stubs; StashDiscoveryWorker does. Without this kick
        // a freshly-created/refreshed mix sits empty ("Building your mix…")
        // until the once-daily periodic sweep happens to run, because the
        // only other drain trigger — the parallel kick in MixBuilderViewModel
        // .save() — races this fill and usually runs before the PENDING rows
        // exist (the Last.fm tag queries here take seconds). Kicking the drain
        // at the END of the fill closes that race for every refresh path
        // (create, manual "Refresh this mix", periodic). Guarded + REPLACE:
        // a test-JVM WorkManager throws, and rapid double-fills coalesce.
        // Loop-break: in materialize-only mode (the post-drain re-link kicked
        // by StashDiscoveryWorker) we must NOT re-kick the drain. Doing so
        // formed a runaway refresh⇄drain cycle that continuously cleared +
        // reinserted every mix. The normal refresh path still kicks it so
        // freshly-queued candidates get drained once.
        if (!materializeOnly) {
            runCatching {
                StashDiscoveryWorker.enqueueOneTime(
                    applicationContext,
                    downloadNetworkPreference.current(),
                    expedited = true,
                )
            }.onFailure { Log.w(TAG, "discovery drain enqueue failed; refresh still succeeded", it) }
        }

        return Result.success()
    }

    /**
     * Output of [materializeMix]. Carries the just-created/updated
     * playlist id (existing return) AND the discovery-survivor track ids
     * the materialization inserted, so [doWork]'s outer cross-mix dedup
     * loop can add them to its accumulating excludeIds set.
     */
    private data class MaterializeResult(
        val playlistId: Long,
        val discoveryIds: List<Long>,
    )

    /**
     * Cross-mix track-dedup priority. Most-restrictive recipes go first
     * so they claim their natural picks; most-permissive last so it
     * picks up the leftovers.
     *
     *  - First Listen (1.0 discovery, library-blind) — has no opinion on
     *    the library pool; claims TAG_GRAPH survivors first.
     *  - Deep Cuts (0.85 discovery) — claims TRACK_SIMILAR survivors and
     *    a sparse library slice next.
     *  - Daily Discover (0.85 discovery) — most permissive on the
     *    library slice; claims ARTIST_SIMILAR survivors last.
     *  - Non-builtins / unknown — last (99).
     *
     * Keyed by name rather than id because builtin id values aren't
     * stable across reseeds.
     */
    private fun recipeDedupPriority(recipe: StashMixRecipeEntity): Int = when (recipe.name) {
        "First Listen" -> 1
        "Deep Cuts" -> 2
        "Daily Discover" -> 3
        else -> 99
    }

    /**
     * Find-or-create a playlist row for this recipe, then replace its
     * tracklist with [tracks] in correct order. Returns a
     * [MaterializeResult] with the playlist_id and the discovery-survivor
     * track ids actually inserted, so the caller can fold those into the
     * cross-mix excludeIds accumulator for subsequent recipes.
     */
    private suspend fun materializeMix(
        recipe: StashMixRecipeEntity,
        tracks: List<TrackEntity>,
        now: Long,
        excludeIds: Set<Long>,
        rotationSeed: Long,
    ): MaterializeResult {
        // Existing playlist: verify it's still there (could have been
        // deleted by the user). If gone, fall through to re-create.
        val existing = recipe.playlistId?.let { playlistDao.getById(it) }

        // ── v0.9.42 idempotency backstop ──────────────────────────────────
        // Compute the discovery survivors (and therefore the FULL ordered
        // trackId list that WOULD be written) BEFORE touching the playlist.
        // This lets us short-circuit a no-op refresh: if the set we're about
        // to write equals what's already there (same ids, same order), we
        // skip the destructive clear + reinsert that causes the visible
        // "tracks vanish then repopulate" flash. This is a backstop for any
        // refresh that re-runs with an identical result (the primary fix is
        // gating the worker loop in DiscoveryDownloadWorker).
        //
        // The discovery computation below depends only on `librarySet` and
        // the recipe (the candidate query is keyed by recipe.id, not by the
        // playlist's current cross-refs), so it is safe to run before the
        // membership is rebuilt.
        // Re-link any Discovery-sourced tracks that this recipe has
        // already accepted on previous refreshes. Without this, every
        // weekly refresh wiped the Discovery slots when the playlist was
        // cleared above, the next orphan-sweep deleted the audio files,
        // and the mix silently degenerated to 100% library tracks — the
        // user's "Stash Discover" ended up being a random unplayed slice
        // of their own imports. See the audit in conversation 2026-04-21.
        //
        // Library tracks already own positions 0..tracks.size-1, so we
        // append discovery survivors after them. Dedup against the set
        // we just inserted in case generator + discovery both surface
        // the same track (possible if a Discovery download completed and
        // then showed up in the user's library in some other way).
        val librarySet = tracks.mapTo(HashSet(tracks.size)) { it.id }
        // v0.9.15: Filter out blocked identities. Without this, a track
        // that was discovered + downloaded + later blocked would re-link
        // into the mix on every refresh because the discovery queue's
        // DONE row is keyed by track id, not identity. `filter` doesn't
        // accept suspend lambdas, so the blocklist check is a manual
        // loop that calls the suspend guard sequentially.
        // v0.9.19 follow-up: cap discovery survivors at the recipe's stated
        // slot count (targetLength * discoveryRatio). DAO query orders by
        // completed_at DESC so newest-DONE survivors win when we have to cut.
        // Library shortfall-fill in MixGenerator is intentionally untouched —
        // total playlist size for Daily Discover settles at up-to-50 (library)
        // + up-to-20 (discovery) = <=70, replacing the previous unbounded growth.
        //
        // v0.9.20: filter via excludeIds; over-fetch so post-filter still
        // fills the cap. Worst case: every excluded id was a survivor — we
        // still come back with `discoveryCap` distinct ids. Bounded — the
        // DAO's ORDER BY completed_at DESC + LIMIT means we just slide the
        // window further down the survivor list.
        val discoveryCap = (recipe.targetLength * recipe.discoveryRatio)
            .roundToInt()
            .coerceAtLeast(0)
        // Generous over-fetch: in pathological pool-overlap cases (e.g. Deep
        // Cuts' TRACK_SIMILAR pool ~95% overlapping First Listen's TAG_GRAPH
        // pool because a user's top-tracks naturally share tags with the
        // wider taste graph) the older limit could exhaust before we found
        // discoveryCap survivors. Headroom is bounded by the total recipe
        // backlog — DAO query has its own LIMIT clause.
        //
        // v0.9.37: swap to playlistDao.getStreamableOrDoneTrackIdsForRecipe
        // so stream-only DONE rows (is_downloaded=0, is_streamable=1) also
        // make it into the Mix playlist. The new DAO method has NO limit
        // parameter (kept the query simple — the spec's Section 8 still
        // owns the per-recipe cap revisit). We trim post-fetch with .take
        // to preserve the over-fetch headroom semantics from above. ORDER
        // BY completed_at DESC inside the query means .take still slides
        // newest-first.
        // #287: consider the recipe's whole DONE backlog (bounded) instead of
        // a thin newest-first window — rotation needs breadth to rotate over.
        val rawCandidateIds = playlistDao
            .getStreamableOrDoneTrackIdsForRecipe(recipe.id)
            .take(SURVIVOR_POOL_LIMIT)
        val nonLibraryCandidates = rawCandidateIds.filter { it !in librarySet }
        // v0.9.21: soft cross-mix dedup. Prefer survivors not already claimed
        // by an earlier-ordered mix; if dedup leaves us below the cap, backfill
        // from the shared pool rather than ship an empty discovery section.
        // Without this, recipes that draw from overlapping Last.fm pools
        // (TRACK_SIMILAR ∩ TAG_GRAPH ≈ everywhere) silently collapsed to
        // library-only when refreshed after their peers had already grabbed
        // the shared candidates. See conversation 2026-05-12: Deep Cuts had
        // 102 recipe-4 downloaded tracks but 0 discovery survivors.
        val (preferredIds, sharedIds) = nonLibraryCandidates
            .partition { it !in excludeIds }
        val survivorCandidates = if (preferredIds.size >= discoveryCap) {
            rotateSurvivorWindow(preferredIds, discoveryCap, rotationSeed)
        } else {
            val backfill = sharedIds.take(discoveryCap - preferredIds.size)
            if (backfill.isNotEmpty()) {
                Log.i(
                    TAG,
                    "'${recipe.name}': dedup left ${preferredIds.size}/$discoveryCap unique survivors; " +
                        "backfilled ${backfill.size} from cross-mix pool",
                )
            }
            preferredIds + backfill
        }
        val discoveryTrackIds = buildList {
            for (trackId in survivorCandidates) {
                if (!blocklistGuard.isBlockedByTrackId(trackId)) add(trackId)
            }
        }
        Log.i(
            TAG,
            "'${recipe.name}' (id=${recipe.id}) link: raw=${rawCandidateIds.size} " +
                "nonLib=${nonLibraryCandidates.size} preferred=${preferredIds.size} " +
                "shared=${sharedIds.size} cap=$discoveryCap linked=${discoveryTrackIds.size} " +
                "excludeIds.size=${excludeIds.size}",
        )
        // The full ordered membership we are about to write: library slice
        // (generator order) followed by discovery survivors.
        // #287 follow-up: a discovery-led mix leads WITH its discoveries —
        // fresh finds own the top of the list, the cover mosaic (built from
        // the first 4 positions), and the first tap of Play. Under the old
        // library-first order a successful rotation was visually invisible:
        // same openers, same art, same count — users read Refresh as a no-op.
        // Library-led mixes keep their library-first order.
        val discoveryFirst = recipe.discoveryRatio >= 0.5f
        val finalOrderedIds = if (discoveryFirst) {
            discoveryTrackIds + tracks.map { it.id }
        } else {
            tracks.map { it.id } + discoveryTrackIds
        }
        val totalCount = finalOrderedIds.size

        // v0.9.42: idempotency short-circuit. If the playlist already exists
        // and its current ordered membership is identical to what we'd write,
        // skip the clear + reinsert entirely — no visible flash, no orphan
        // churn. We still leave the existing art/count alone in this branch
        // because membership (hence cover + count) is unchanged.
        if (existing != null) {
            val current = playlistDao.getOrderedTrackIdsForPlaylist(existing.id)
            if (current == finalOrderedIds) {
                Log.i(
                    TAG,
                    "'${recipe.name}' (id=${existing.id}) unchanged — skipping re-materialize",
                )
                // Name can still drift (e.g. a recipe rename) even when the
                // track set matches; keep that cheap, non-destructive update.
                playlistDao.updateName(existing.id, recipe.name)
                return MaterializeResult(existing.id, discoveryTrackIds)
            }
        }

        // Find-or-create the backing playlist row.
        val playlistId = if (existing != null) {
            playlistDao.clearPlaylistTracks(existing.id)
            playlistDao.updateName(existing.id, recipe.name)
            existing.id
        } else {
            val firstArt = tracks.firstNotNullOfOrNull { it.albumArtUrl }
            val newPlaylist = PlaylistEntity(
                name = recipe.name,
                source = MusicSource.BOTH,
                sourceId = "stash_mix_${recipe.id}",
                type = PlaylistType.STASH_MIX,
                trackCount = tracks.size,
                artUrl = firstArt,
                syncEnabled = true,
                isActive = true,
            )
            playlistDao.insert(newPlaylist)
        }

        // Rebuild track membership in the final order computed above.
        val nowInstant = Instant.ofEpochMilli(now)
        finalOrderedIds.forEachIndexed { position, trackId ->
            playlistDao.insertCrossRef(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = position,
                    addedAt = nowInstant,
                )
            )
        }

        // v0.9.40: build an album mosaic from the FULL linked track set
        // (library slice + stream-only discovery survivors), not just the
        // library `tracks` — otherwise 100%-discovery mixes (every custom mix
        // and First Listen) get a blank cover even though their stream-only
        // tracks carry album art. Up to 4 distinct arts, "|"-joined: the mix
        // card renders a tile mosaic, and single-image call sites take the
        // first (PlaylistMapper). Recomputed every refresh, so the mosaic
        // fills in once stub art is backfilled. Query runs AFTER the cross-refs
        // above so it sees the full membership.
        val coverArtUrls = playlistDao.getCoverArtUrlsForPlaylist(playlistId, limit = 4)
        if (coverArtUrls.isNotEmpty()) {
            playlistDao.updateArtUrl(playlistId, coverArtUrls.joinToString("|"))
        }
        playlistDao.updateTrackCount(playlistId, totalCount)
        return MaterializeResult(playlistId, discoveryTrackIds)
    }

    /**
     * v0.9.16: Per-recipe candidate generation, dispatched by the
     * recipe's [StashMixRecipeEntity.seedStrategy]. Each strategy
     * inputs different signals from the user's personas + library;
     * [MixSeedGenerator] handles the Last.fm calls + rate limiting and
     * returns the candidate list, which we hand off to
     * [MixGenerator.queueDiscoveryCandidates] for blocklist + dedup
     * filtering before insertion.
     */
    private suspend fun queueDiscoveryForRecipe(
        recipe: StashMixRecipeEntity,
        personas: LastFmPersonas,
    ) {
        val strategy = MixSeedStrategy.fromStored(recipe.seedStrategy)
        if (strategy == MixSeedStrategy.NONE) return

        // TAG_GRAPH recipes seed from their OWN resolved tags (genres+moods+era),
        // not the user's global top tags — the custom-mix + retuned-Deep-Cuts path.
        // Other strategies keep the persona-seeded path below, unchanged.
        if (strategy == MixSeedStrategy.TAG_GRAPH) {
            queueTagSeededDiscovery(recipe)
            return
        }

        val nowMs = System.currentTimeMillis()
        val since = nowMs - AFFINITY_LOOKBACK_DAYS * 24 * 60 * 60 * 1000
        val recentSince = nowMs - RECENT_LISTENING_LOOKBACK_MS

        // #287: yesterday's listening LEADS the seed chain — a "Daily"
        // discover should chase what the user actually played in the last
        // day or two, not a month-old persona. The older-taste tiers
        // (persona 1-month > local 180d > library-top) become the blend
        // backfill so a thin recent window never narrows discovery to a
        // single-artist echo chamber. Recent-first seeds also vary the
        // Last.fm query keys day to day, which keeps the proxy's edge cache
        // from pinning the candidate pools for its full TTL.
        val recentArtists = listeningEventDao
            .getTopArtistsSince(recentSince, TOP_ARTISTS_LIMIT)
            .map { it.artist }
        val affinityArtists = personas.topArtistsByPeriod[LastFmPeriod.ONE_MONTH]
            ?.takeIf { it.isNotEmpty() }
            ?.take(TOP_ARTISTS_LIMIT)?.map { it.name }
            ?: listeningEventDao.getTopArtistsSince(since, TOP_ARTISTS_LIMIT)
                .map { it.artist }
                .ifEmpty { trackDao.getTopArtistsByTrackCount(TOP_ARTISTS_LIMIT) }
        val seedArtists = blendRecentFirst(recentArtists, affinityArtists, TOP_ARTISTS_LIMIT)

        // v0.9.20 PR 8: seedTracks gets the same fallback chain as
        // seedArtists (persona > local events > library top-by-LFM-playcount),
        // now likewise led by the recent-listening blend.
        val recentTracks = listeningEventDao
            .getTopTracksByLocalPlays(recentSince, 20)
            .map { it.artist to it.title }
        val affinityTracks: List<Pair<String, String>> = personas.topTracksByPeriod[LastFmPeriod.ONE_MONTH]
            ?.takeIf { it.isNotEmpty() }
            ?.take(20)?.map { it.artist to it.title }
            ?: listeningEventDao.getTopTracksByLocalPlays(since, 20)
                .map { it.artist to it.title }
                .ifEmpty {
                    trackDao.getTopTracksByLfmPlaycount(20)
                        .map { it.artist to it.title }
                }
        val seedTracks = blendRecentFirst(recentTracks, affinityTracks, 20)

        val topTags = mixGenerator.computeUserTopTags(limit = 10)

        val candidates = seedGenerator.generate(
            strategy = strategy,
            seedArtists = seedArtists,
            topTags = topTags,
            seedTracks = seedTracks,
            personas = personas,
        )
        if (candidates.isEmpty()) return

        // v0.9.20: pre-filter against library + skip-ban so discovery_queue
        // PENDING rows represent genuinely-new music, not "rediscovery" hits.
        val libraryKeys = trackDao.getLibraryCanonicalKeys().toHashSet()
        val skipBannedKeys = trackSkipEventDao
            .getEarlySkipBannedCanonicalKeys(
                minSkips = DISCOVERY_SKIP_BAN_MIN_COUNT,
                sinceMs = System.currentTimeMillis() - DISCOVERY_SKIP_BAN_WINDOW_MS,
                maxPositionMs = DISCOVERY_SKIP_BAN_MAX_POSITION_MS,
            )
            .toHashSet()

        val filtered = candidates.filter { candidate ->
            val key = canonicalKey(candidate.artist, candidate.title)
            key !in libraryKeys && key !in skipBannedKeys
        }

        val final = if (filtered.size >= MIN_DISCOVERY_POOL_AFTER_FILTER) {
            filtered
        } else {
            // Fallback: top off with TAG_GRAPH candidates (different strategy
            // typically yields different artists, so library overlap is lower).
            // Same filters applied. withTimeout protects WorkManager's 10-minute
            // budget — mirrors the existing 30s persona-fetch timeout.
            val tagFallback = runCatching {
                withTimeout(SEED_FALLBACK_TIMEOUT_MS) {
                    seedGenerator.generate(
                        strategy = MixSeedStrategy.TAG_GRAPH,
                        seedArtists = emptyList(),
                        topTags = topTags,
                        seedTracks = emptyList(),
                        personas = personas,
                    )
                }
            }.getOrElse {
                Log.w(TAG, "'${recipe.name}': TAG_GRAPH fallback timed out / failed", it)
                emptyList()
            }.filter { candidate ->
                val key = canonicalKey(candidate.artist, candidate.title)
                key !in libraryKeys && key !in skipBannedKeys
            }
            Log.i(
                TAG,
                "'${recipe.name}': filtered pool (${filtered.size}) below floor; " +
                    "appending ${tagFallback.size} TAG_GRAPH fallback candidates",
            )
            filtered + tagFallback
        }

        if (final.isEmpty()) {
            Log.w(TAG, "'${recipe.name}': all candidates filtered out (library + skips); skipping queue")
            return
        }

        Log.i(
            TAG,
            "'${recipe.name}': ${final.size} candidates via $strategy " +
                "(${candidates.size - filtered.size} filtered as library/banned)",
        )
        mixGenerator.queueDiscoveryCandidates(recipe, final)
    }

    /**
     * Tag-seeded discovery for TAG_GRAPH recipes (custom mixes + Deep Cuts).
     * Pool comes from the recipe's resolved tags via [TagPoolBuilder] — NOT from
     * tracks similar to the user's library — which is what fixes Deep Cuts. Applies
     * the same library/skip pre-filter as the persona path, plus a relaxation
     * ladder (drop era -> drop moods) when the filtered pool is below the floor.
     */
    private suspend fun queueTagSeededDiscovery(recipe: StashMixRecipeEntity) {
        val userTopTags = mixGenerator.computeUserTopTags(limit = 10)
        val userTopArtists = trackDao.getTopArtistsByTrackCount(TOP_ARTISTS_LIMIT)
            .map { it.trim().lowercase() }.toHashSet()

        val libraryKeys = trackDao.getLibraryCanonicalKeys().toHashSet()
        val skipBannedKeys = trackSkipEventDao.getEarlySkipBannedCanonicalKeys(
            minSkips = DISCOVERY_SKIP_BAN_MIN_COUNT,
            sinceMs = System.currentTimeMillis() - DISCOVERY_SKIP_BAN_WINDOW_MS,
            maxPositionMs = DISCOVERY_SKIP_BAN_MAX_POSITION_MS,
        ).toHashSet()

        fun filterNew(c: List<MixGenerator.DiscoveryCandidate>) = c.filter {
            val key = canonicalKey(it.artist, it.title)
            key !in libraryKeys && key !in skipBannedKeys
        }

        val baseTags = RecipeTagResolver.resolve(recipe, userTopTags)
        val pool = filterNew(tagPoolBuilder.build(baseTags, recipe.tagSampleDepth, userTopArtists))
            .toMutableList()
        val seen = pool.mapTo(HashSet()) { canonicalKey(it.artist, it.title) }

        if (pool.size < MIN_DISCOVERY_POOL_AFTER_FILTER) {
            val rungs = buildList {
                if (recipe.eraStartYear != null) add(recipe.copy(eraStartYear = null, eraEndYear = null))
                if (recipe.moodKeysCsv.isNotEmpty()) {
                    add(recipe.copy(eraStartYear = null, eraEndYear = null, moodKeysCsv = ""))
                }
            }
            for (rung in rungs) {
                if (pool.size >= MIN_DISCOVERY_POOL_AFTER_FILTER) break
                val rungTags = RecipeTagResolver.resolve(rung, userTopTags)
                val more = filterNew(tagPoolBuilder.build(rungTags, rung.tagSampleDepth, userTopArtists))
                    .filter { seen.add(canonicalKey(it.artist, it.title)) }
                pool.addAll(more)
                Log.i(TAG, "'${recipe.name}': relaxed tag pool to ${pool.size} (dropped era/mood rung)")
            }
        }

        if (pool.isEmpty()) {
            Log.w(TAG, "'${recipe.name}': tag-seeded pool empty after filtering; skipping queue")
            return
        }
        Log.i(TAG, "'${recipe.name}': queueing ${pool.size} tag-seeded candidates")
        mixGenerator.queueDiscoveryCandidates(recipe, pool)
    }

    /**
     * Canonical key used by the discovery pre-filter — must match the
     * format the DAOs store and return: TrackMatcher's normalization
     * applied to artist and title, joined with "|".
     *
     * The sync writer uses this same TrackMatcher instance to populate
     * tracks.canonical_artist / canonical_title, so the keys produced
     * here match the keys returned by getLibraryCanonicalKeys() and
     * getEarlySkipBannedCanonicalKeys().
     */
    private fun canonicalKey(artist: String, title: String): String =
        "${trackMatcher.canonicalArtist(artist)}|${trackMatcher.canonicalTitle(title)}"

    /**
     * v0.9.16: Snapshot the user's period-sliced top tracks/artists once
     * per refresh run so each recipe can pull whichever slice it needs
     * (Daily Discover → 1month, Throwback → overall − 3month, etc.)
     * without redundant network calls. Sequential with a small inter-call
     * delay to stay polite under Last.fm's rate ceiling. Wrapped by
     * [withTimeout] at the call site — failures here surface as the
     * cancelled-coroutine path which the caller turns into [LastFmPersonas.EMPTY].
     */
    private suspend fun fetchPersonas(username: String): LastFmPersonas {
        val periods = listOf(
            LastFmPeriod.SEVEN_DAY,
            LastFmPeriod.ONE_MONTH,
            LastFmPeriod.THREE_MONTH,
            LastFmPeriod.SIX_MONTH,
            LastFmPeriod.OVERALL,
        )
        val tracks = mutableMapOf<LastFmPeriod, List<LastFmTopTrack>>()
        val artists = mutableMapOf<LastFmPeriod, List<LastFmTopArtist>>()
        for (period in periods) {
            tracks[period] = lastFmApiClient.getUserTopTracks(username, period, limit = 100)
                .getOrNull().orEmpty()
            artists[period] = lastFmApiClient.getUserTopArtists(username, period, limit = 50)
                .getOrNull().orEmpty()
            delay(SIMILAR_REQUEST_INTERVAL_MS)
        }
        return LastFmPersonas(tracks, artists)
    }
}
