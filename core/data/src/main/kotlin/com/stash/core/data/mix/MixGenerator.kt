package com.stash.core.data.mix

import android.util.Log
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.record.MixTaste
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure materializer for a [StashMixRecipeEntity] — given the recipe and
 * read access to the library, it produces an ordered list of `TrackEntity`
 * rows that the refresh worker writes into the recipe's playlist.
 *
 * ## Scoring model (v0.9.16)
 *
 * Every candidate track is assigned a score that linearly combines:
 *
 *  - **Affinity** (`buildAffinityMap`): in-Stash plays in the last 180
 *    days, log-normalized and exponentially decayed (30-day half-life),
 *    plus a Last.fm cross-source playcount supplement. Recipe's
 *    [StashMixRecipeEntity.affinityBias] shifts the weight:
 *      - positive bias (+) → heavy rotation favorites bubble up
 *      - negative bias (−) → Rediscovery surfaces tracks you *have* liked
 *        but haven't played recently
 *
 *  - **Tag cosine** (`buildTagCosineMap`): cosine similarity between the
 *    track's tag-weight vector and the user's L2-normalized tag-affinity
 *    vector (computed from decayed listening history). Replaces the
 *    older "sum of include-tag weights" heuristic — every recipe now
 *    gets a tag-affinity signal, not just those with explicit tags.
 *
 *  - **Completion** (`buildCompletionMap`): completed-listen ratio over
 *    the last 60 days. Tracks the user habitually finishes get a small
 *    boost; unknown tracks default to 0.5 (neutral).
 *
 *  - **Loved boost**: additive bonus for Last.fm-loved tracks.
 *
 *  - **Skip penalty** (`buildSkipPenaltyMap`): subtracted from the
 *    score when a track's 14-day skip-rate is high (linear ramp from
 *    0.4, full penalty at 0.6+).
 *
 * Freshness is a hard pre-filter (Step 5), not a scoring term — a small
 * jitter is added at sort time so the same score doesn't produce
 * identical ordering day-to-day.
 *
 * ## Discovery slots
 *
 * The recipe's `discovery_ratio` reserves that fraction of the target
 * length for tracks not yet in the library. This generator does NOT fetch
 * those — it only queues candidate entries into [DiscoveryQueueEntity]
 * for the [com.stash.core.data.sync.workers.StashDiscoveryWorker] to
 * resolve asynchronously. If no discovery tracks are ready yet, the
 * library-only portion stretches to fill the mix (users never see an
 * empty-looking mix because discovery didn't finish).
 */
@Singleton
class MixGenerator @Inject constructor(
    private val trackDao: TrackDao,
    private val trackTagDao: TrackTagDao,
    private val listeningEventDao: ListeningEventDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val trackSkipEventDao: com.stash.core.data.db.dao.TrackSkipEventDao,
) {

    companion object {
        private const val TAG = "StashMixRefresh"

        /** Recency window for affinity. Decay half-life is what controls "current"-ness. */
        private const val AFFINITY_WINDOW_MS = 180L * 24 * 60 * 60 * 1000

        /** Half-life of the affinity exponential decay, in milliseconds (30 days). */
        private const val AFFINITY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

        /** Window for skip-rate computation. Shorter than affinity — skips age fast. */
        private const val SKIP_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        /** Window for completion-rate computation. */
        private const val COMPLETION_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

        private const val BASE_AFFINITY_WEIGHT = 0.40f      // was 0.50; tag-cosine takes some
        private const val BASE_TAG_WEIGHT      = 0.35f      // was 0.30
        private const val BASE_COMPLETION_W    = 0.10f      // NEW
        private const val LOVED_BOOST          = 0.5f       // additive, not weight
        private const val SKIP_PENALTY_HEAVY   = 0.6f       // when skip-rate >= ramp
        private const val SKIP_PENALTY_RAMP    = 0.6f       // skip-rate above which heavy penalty kicks in

        /**
         * Scalar applied to the Last.fm-user-playcount term INSIDE
         * buildAffinityMap, before the outer BASE_AFFINITY_WEIGHT
         * multiplication. Intentionally lower than parity with local
         * plays — Last.fm playcount counts every scrobble across every
         * service the user ever connected, so a single track with 200
         * lifetime LFM plays shouldn't outweigh a 30-play in-Stash
         * track from this month. Net effect: LFM contributes ~12% of
         * the affinity weight (0.3 * 0.40), local contributes ~40%.
         * Rebalance only after on-device data shows the bias is wrong.
         */
        private const val LFM_PLAYCOUNT_W      = 0.3f
        private const val SORT_JITTER          = 0.10f      // was 0.05; ~12% of nominal score range
    }

    /**
     * Produces the finalized track list for [recipe]. The worker calling
     * this replaces the playlist_tracks rows for [recipe.playlistId] with
     * this ordering.
     *
     * v0.9.20: [excludeIds] is the cross-mix dedup primitive — when the
     * refresh worker iterates multiple recipes back-to-back, it accumulates
     * track ids already claimed by earlier recipes and passes them here so
     * the current recipe can't re-pick them.
     *
     * [taste]: Your record's taste ([com.stash.core.data.record.RecordMixTaste], read once per run), or null. With one, the songs
     * it keeps out leave the pool, and a song's affinity is the larger of its own and its taste weight's (stash-player
     * `discover/mix.ts` `librarySlice`). Null: exactly as before.
     */
    suspend fun generate(
        recipe: StashMixRecipeEntity,
        excludeIds: Set<Long> = emptySet(),
        taste: MixTaste? = null,
    ): List<TrackEntity> {
        // Library slots = targetLength * (1 - discoveryRatio); see Step 7.
        val librarySlots = (recipe.targetLength * (1f - recipe.discoveryRatio)).toInt()
            .coerceAtLeast(0)
        // No library slots and no shortfall backfill (Step 8 is gated on a
        // ratio below 0.5): the result is empty whatever the library holds, so
        // skip scoring the whole library ("Recommended by Last.fm" at 1.0, and
        // custom mixes at the "Discover" end of the slider).
        if (librarySlots == 0 && recipe.discoveryRatio >= 0.5f) return emptyList()

        // Step 1: candidate pool — start from every downloaded,
        // non-blacklisted track in the library. v0.9.20: filter through
        // excludeIds first (cheap O(n) set lookup, before any other filtering).
        val rawPool = trackDao.getAllDownloaded()
        val pool00 = if (excludeIds.isEmpty()) rawPool else rawPool.filter { it.id !in excludeIds }
        // Your record's taste: the songs it keeps out (hidden on Spotify, always skipped) never enter the pool
        val pool0 = if (taste == null || taste.avoid.isEmpty()) {
            pool00
        } else {
            pool00.filterNot { taste.avoids(it.title, it.artist) }.also { kept ->
                Log.i(TAG, "'${recipe.name}': your record avoided ${pool00.size - kept.size} of ${pool00.size} library songs")
            }
        }

        // Step 2: the era filter runs last (Step 5b), because it may be relaxed
        // when nothing in what the other filters left matches it.
        var pool = pool0

        // Step 3: include-tag filter (one DAO hit if recipe has tags).
        val includeTags = recipe.includeTagsCsv.splitTrim()
        if (includeTags.isNotEmpty()) {
            val taggedIds = trackTagDao.getTrackIdsByTags(includeTags).toSet()
            pool = pool.filter { it.id in taggedIds }
        }

        // Step 4: exclude-tag hard filter.
        val excludeTags = recipe.excludeTagsCsv.splitTrim()
        if (excludeTags.isNotEmpty()) {
            val excludedIds = trackTagDao.getTrackIdsMatchingAny(excludeTags).toSet()
            pool = pool.filter { it.id !in excludedIds }
        }

        // Step 5: freshness filter — exclude tracks played inside the window.
        if (recipe.freshnessWindowDays > 0) {
            val since = System.currentTimeMillis() -
                recipe.freshnessWindowDays * 24L * 60 * 60 * 1000
            val recentlyPlayedIds = listeningEventDao
                .getTrackIdsPlayedSince(since)
                .toSet()
            pool = pool.filter { it.id !in recentlyPlayedIds }
        }

        // Step 5b: era and mood filters.
        pool = filterByEra(pool, recipe)
        pool = filterByMood(pool, recipe)

        Log.i(TAG, "'${recipe.name}': library pool ${pool.size} of ${rawPool.size} downloaded${if (taste != null) " (with your record)" else ""}")
        if (pool.isEmpty()) return emptyList()

        // Step 6: score + sort.
        val userVector = buildUserTagAffinityVector()
        val affinityMap = buildAffinityMap(pool, taste)
        val tagCosineMap = buildTagCosineMap(pool, userVector)
        val completionMap = buildCompletionMap(pool)
        val skipPenaltyMap = buildSkipPenaltyMap(pool)

        val wAff = BASE_AFFINITY_WEIGHT + recipe.affinityBias * 0.3f
        val wTag = BASE_TAG_WEIGHT
        val wCmp = BASE_COMPLETION_W

        // Seed the score jitter deterministically per recipe + day. An
        // unseeded Random re-shuffled the library slice on every refresh, so
        // any recipe with a fractional library slot (every MULTI-genre custom
        // mix, whose discoveryRatio rounds below 1.0) produced a different
        // finalOrderedIds each pass. That defeated StashMixRefreshWorker
        // .materializeMix's idempotency short-circuit and let the refresh loop
        // visibly clear+reinsert the mix ("load 40, then repopulate"). Seeding
        // by recipe+day keeps a day's refreshes identical (short-circuit fires)
        // while still rotating the order daily.
        val jitterRng = Random(recipe.id * 1_000_003L xor (System.currentTimeMillis() / 86_400_000L))
        val scored = pool.map { track ->
            val aff = affinityMap[track.id] ?: 0f
            val tag = tagCosineMap[track.id] ?: 0f
            val cmp = completionMap[track.id] ?: 0.5f         // unknown -> neutral
            val loved = if (track.lastfmUserLoved) LOVED_BOOST else 0f
            val skip = skipPenaltyMap[track.id] ?: 0f
            val score = aff * wAff +
                tag * wTag +
                cmp * wCmp +
                loved -
                skip +
                jitterRng.nextFloat() * SORT_JITTER
            track to score
        }

        // Step 7: library slots = targetLength * (1 - discoveryRatio), but
        // if we ran out of library candidates we fill up to targetLength.
        // Avoid back-to-back same-artist within the first stretch.
        val ordered = scored.sortedByDescending { it.second }.map { it.first }

        val picked = pickWithArtistSpread(
            candidates = ordered,
            desired = librarySlots.coerceAtMost(pool.size),
        )

        // Step 8: shortfall backfill from the remaining library pool.
        // v0.9.20: gate raised from `< 1.0f` to `< 0.5f`. Recipes that should
        // be substantially discovery-driven (>= 50% by ratio) must not silently
        // degrade to library when discovery is sparse — that's exactly what
        // produced the "library-heavy mixes" symptom on existing installs.
        // A sparser-but-honest mix is better than a deceptively library-filled
        // one. Library-only recipes (ratio == 0) and lightly-discovery recipes
        // (< 0.5) still get the original full-fill behavior.
        if (recipe.discoveryRatio < 0.5f) {
            val shortfall = recipe.targetLength - picked.size
            if (shortfall > 0 && picked.size < pool.size) {
                val extra = ordered.filter { it !in picked }.take(shortfall)
                return picked + extra
            }
        }

        return picked
    }

    /**
     * Build per-track affinity scores in the range [0, 1]. Combines two
     * signals:
     *  - In-Stash plays in the last 180 days, log-normalized by the
     *    library's max count and exponentially decayed by recency
     *    (30-day half-life from [AFFINITY_HALF_LIFE_MS]).
     *  - Last.fm cross-source playcount (only present after Last.fm
     *    track-info enrichment) scaled by [LFM_PLAYCOUNT_W].
     *
     * Tracks the user has neither played in-Stash nor scrobbled to
     * Last.fm get a zero entry (omitted from the map).
     *
     * With Your record's [taste], a track's affinity is the larger of that and
     * `0.6 · its taste weight` (stash-player `discover/mix.ts` `librarySlice`),
     * and a pool with no other signal yet still gets the taste's. Internal for
     * its tests.
     */
    internal suspend fun buildAffinityMap(pool: List<TrackEntity>, taste: MixTaste? = null): Map<Long, Float> {
        val now = System.currentTimeMillis()
        val since = now - AFFINITY_WINDOW_MS
        val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)
        val poolIds = pool.mapTo(HashSet(pool.size)) { it.id }
        // Your record's weights count even when nothing else has signal yet (a first mix after an import)
        val tasteWeights = taste != null && taste.weights.isNotEmpty()

        if (rows.isEmpty() && pool.none { (it.lastfmUserPlaycount ?: 0) > 0 } && !tasteWeights) {
            return emptyMap()
        }

        val maxPlays = (rows.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)
        val maxLfmPlays = pool.maxOfOrNull { it.lastfmUserPlaycount ?: 0 }?.coerceAtLeast(1) ?: 1

        val byId = rows.associateBy { it.trackId }
        val result = HashMap<Long, Float>(pool.size)
        for (track in pool) {
            if (track.id !in poolIds) continue
            val row = byId[track.id]
            // In-Stash plays with exponential decay
            val localTerm = if (row != null) {
                val logNorm = (ln(1f + row.plays.toFloat()) /
                    ln(1f + maxPlays.toFloat())).coerceIn(0f, 1f)
                val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
                val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
                logNorm * decay
            } else 0f
            // Last.fm cross-source playcount (only present after enrichment)
            val lfmTerm = track.lastfmUserPlaycount?.let { lpc ->
                (ln(1f + lpc.toFloat()) /
                    ln(1f + maxLfmPlays.toFloat())).coerceIn(0f, 1f) * LFM_PLAYCOUNT_W
            } ?: 0f
            val own = (localTerm + lfmTerm).coerceIn(0f, 1f)
            // What this phone plays, or what the imported years say, whichever is more (mix.ts: max(own, 0.6 · w / max))
            val combined = if (tasteWeights) {
                maxOf(own, (taste!!.weightOf(track.title, track.artist) * MixTaste.TASTE_AFFINITY).toFloat()).coerceIn(0f, 1f)
            } else {
                own
            }
            if (combined > 0f) result[track.id] = combined
        }
        return result
    }

    /**
     * v0.9.16: Build the L2-normalized user tag-affinity vector by
     * weighting each track's tag vector by its (decayed) play weight
     * and summing. Used as one half of the cosine-similarity scoring
     * term against each candidate's own tag vector.
     *
     * Filters the `__untaggable__` sentinel rows that
     * [com.stash.core.data.sync.workers.TagEnrichmentWorker] writes
     * for tracks Last.fm couldn't tag.
     */
    private suspend fun buildUserTagAffinityVector(): Map<String, Float> {
        val now = System.currentTimeMillis()
        val since = now - AFFINITY_WINDOW_MS
        val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)
        if (rows.isEmpty()) return emptyMap()

        val tagsByTrack = loadTagVectors(rows.map { it.trackId })
        val plays = rows.map { row ->
            val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
            val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
            val weight = ln(1f + row.plays.toFloat()) * decay
            val tags = tagsByTrack[row.trackId].orEmpty()
            UserTagAffinity.PlayWithTags(weight = weight, tags = tags)
        }
        return UserTagAffinity.compute(plays)
    }

    /**
     * Tag-weight vectors (lowercased tag to weight) for [trackIds], read in a
     * few bind-chunked queries instead of one query per track. Drops the
     * `__untaggable__` sentinel; tracks with no tags are absent.
     */
    private suspend fun loadTagVectors(trackIds: List<Long>): Map<Long, Map<String, Float>> {
        if (trackIds.isEmpty()) return emptyMap()
        return trackTagDao.getByTracks(trackIds)
            .asSequence()
            .filter { it.tag != "__untaggable__" }
            .groupBy({ it.trackId }, { it.tag.lowercase() to it.weight })
            .mapValues { (_, pairs) -> pairs.toMap() }
    }

    /**
     * v0.9.16: Per-candidate cosine similarity against the user's tag
     * vector. Replaces the old "sum of include-tag weights" heuristic
     * — now every recipe benefits from tag affinity, not just those
     * with explicit include-tags.
     */
    private suspend fun buildTagCosineMap(
        pool: List<TrackEntity>,
        userVector: Map<String, Float>,
    ): Map<Long, Float> {
        if (userVector.isEmpty()) return emptyMap()
        val tagsByTrack = loadTagVectors(pool.map { it.id })
        val result = HashMap<Long, Float>(pool.size)
        for (track in pool) {
            val tags = tagsByTrack[track.id] ?: continue
            if (tags.isEmpty()) continue
            result[track.id] = UserTagAffinity.cosine(tags, userVector)
        }
        return result
    }

    /**
     * v0.9.16: Top-N user tags ordered by tag-affinity weight. Used by
     * [com.stash.core.data.sync.workers.StashMixRefreshWorker] to drive
     * the TAG_GRAPH seed strategy.
     *
     * v0.9.19: when the listening-affinity vector is empty (fresh install,
     * recently played tracks not yet enriched, etc.) falls back to the
     * library-wide tag histogram. The histogram represents "what kind of
     * music this user collects" — the right anchor for First Listen's
     * "wider net" semantics when there's no per-play signal yet. Returns
     * an empty list ONLY when the user has zero tags anywhere in
     * `track_tags` (truly fresh install, enrichment hasn't run a single
     * batch yet) — at which point TAG_GRAPH-driven recipes correctly
     * stay empty until the user's library has any tag data.
     */
    suspend fun computeUserTopTags(limit: Int = 10): List<String> {
        val vector = buildUserTagAffinityVector()
        if (vector.isNotEmpty()) {
            return vector.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }
        return trackTagDao.getTagHistogram()
            .asSequence()
            .filter { it.tag != "__untaggable__" }
            .take(limit)
            .map { it.tag }
            .toList()
    }

    /**
     * v0.9.16: Per-track completion rate over the last 60 days. Tracks
     * with no listening history get omitted (caller defaults to 0.5
     * neutral so brand-new tracks aren't penalized).
     */
    private suspend fun buildCompletionMap(pool: List<TrackEntity>): Map<Long, Float> {
        val since = System.currentTimeMillis() - COMPLETION_WINDOW_MS
        val ids = pool.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        val rows = listeningEventDao.getCompletionStatsSince(ids, since)
        return rows.associate {
            it.trackId to (it.completed.toFloat() / it.total.coerceAtLeast(1).toFloat())
        }
    }

    /**
     * v0.9.16: Per-track skip-rate penalty over the last 14 days.
     * Tracks with fewer than 3 total encounters are excluded (not
     * enough signal). Skip-rate >= [SKIP_PENALTY_RAMP] gets the
     * shadow-block-grade [SKIP_PENALTY_HEAVY] penalty; skip-rate
     * between 0.4 and the ramp gets a linear ramp so a track on
     * its way out of rotation degrades smoothly instead of cliff-
     * dropping.
     */
    private suspend fun buildSkipPenaltyMap(pool: List<TrackEntity>): Map<Long, Float> {
        val since = System.currentTimeMillis() - SKIP_WINDOW_MS
        val ids = pool.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        val rows = trackSkipEventDao.getSkipStatsSince(ids, since)
        return rows.mapNotNull { row ->
            val total = row.skips + row.plays
            if (total < 3) return@mapNotNull null  // not enough data
            val rate = row.skips.toFloat() / total
            val penalty = when {
                rate >= SKIP_PENALTY_RAMP -> SKIP_PENALTY_HEAVY              // shadow-block-ish
                rate >= 0.4f -> (rate - 0.4f) / 0.2f * 0.4f                   // linear ramp
                else -> 0f
            }
            if (penalty > 0f) row.trackId to penalty else null
        }.toMap()
    }

    /**
     * Greedy artist-spread pick. Walks [candidates] in score order and
     * takes each track unless its artist was the previous pick — in which
     * case we look ahead to the next slot and insert the current track
     * later. Prevents a mix starting with 4 straight same-artist songs
     * even when they have the highest scores.
     */
    private fun pickWithArtistSpread(
        candidates: List<TrackEntity>,
        desired: Int,
    ): List<TrackEntity> {
        if (desired <= 0 || candidates.isEmpty()) return emptyList()
        val result = ArrayList<TrackEntity>(desired)
        val remaining = ArrayDeque(candidates)
        while (result.size < desired && remaining.isNotEmpty()) {
            val next = remaining.removeFirst()
            val prevArtist = result.lastOrNull()?.artist?.lowercase()
            if (prevArtist != null && next.artist.lowercase() == prevArtist) {
                // Find a different-artist track in the lookahead.
                val swapIdx = remaining.indexOfFirst { it.artist.lowercase() != prevArtist }
                if (swapIdx >= 0) {
                    val swap = remaining.removeAt(swapIdx)
                    result += swap
                    remaining.addFirst(next) // put original back at the head
                    continue
                }
            }
            result += next
        }
        return result
    }

    /**
     * Keeps the tracks Last.fm tags with the recipe's decade(s) ("80s", via
     * [RecipeTagResolver.decadeTag], the same tags the discovery side seeds
     * from). A Mix Builder era chip means *released* in that decade.
     *
     * This used to compare the era with the year of `date_added`, the day the
     * track entered Stash, so an "80s" mix kept no library tracks at all for a
     * library built in recent years: the mix was empty at the "Familiar" end
     * of the slider, and the library share was always empty otherwise.
     * TrackEntity has no release year, so the decade tag is the best signal
     * there is. When no remaining candidate carries one (tag enrichment has
     * not reached them, or the library has no music from that era), the era
     * is dropped for the library slice rather than leaving it empty, the
     * same relaxation the refresh worker applies to the discovery pool.
     */
    private suspend fun filterByEra(
        pool: List<TrackEntity>,
        recipe: StashMixRecipeEntity,
    ): List<TrackEntity> {
        val startYear = recipe.eraStartYear ?: recipe.eraEndYear ?: return pool
        val endYear = recipe.eraEndYear ?: startYear
        if (pool.isEmpty()) return pool
        val decadeTags = (startYear / 10 * 10..endYear step 10).map { RecipeTagResolver.decadeTag(it) }
        if (decadeTags.isEmpty()) return pool
        val inEraIds = trackTagDao.getTrackIdsByTags(decadeTags).toSet()
        return pool.filter { it.id in inEraIds }.ifEmpty { pool }
    }

    /**
     * Keeps the tracks tagged with any of the recipe's mood tags
     * ([MoodTagMap.expand]: "chill" covers chillout, mellow, downtempo...).
     * Moods used to steer only discovery seeding, so a "Chill" mix at the
     * "Familiar" end of the slider was the user's heaviest rotation whatever
     * its mood. Relaxed like [filterByEra] when nothing left matches.
     */
    private suspend fun filterByMood(
        pool: List<TrackEntity>,
        recipe: StashMixRecipeEntity,
    ): List<TrackEntity> {
        val moodTags = MoodTagMap.expand(recipe.moodKeysCsv)
        if (moodTags.isEmpty() || pool.isEmpty()) return pool
        val moodIds = trackTagDao.getTrackIdsByTags(moodTags).toSet()
        return pool.filter { it.id in moodIds }.ifEmpty { pool }
    }

    /**
     * After a refresh, queue discovery candidates. Pulls the user's top
     * artists from [ListeningEventDao.getTopArtistsSince], fetches similar
     * artists per seed, takes the top tracks from each, and files
     * `discovery_queue` rows that the [StashDiscoveryWorker] will resolve.
     *
     * The worker itself performs no Last.fm calls — it's pure Kotlin. That
     * keeps the expensive network work off the refresh critical path.
     * Called from [com.stash.core.data.sync.workers.StashMixRefreshWorker]
     * after each successful recipe refresh with the list of seed artists.
     */
    suspend fun queueDiscoveryCandidates(
        recipe: StashMixRecipeEntity,
        similarArtistSuggestions: List<DiscoveryCandidate>,
        taste: MixTaste? = null,
    ) {
        if (similarArtistSuggestions.isEmpty()) return
        var avoided = 0
        val toInsert = similarArtistSuggestions.mapNotNull { cand ->
            // Your record's taste never offers a song it keeps out (mix.ts `discoveryWindow`)
            if (taste != null && taste.avoids(cand.title, cand.artist)) {
                avoided++
                return@mapNotNull null
            }
            // v0.9.15: Skip blocklisted identities so the same blocked
            // artist+title doesn't get re-queued every refresh and end up
            // re-discovered via StashDiscoveryWorker.
            if (blocklistGuard.isBlocked(
                    artist = cand.artist, title = cand.title,
                    spotifyUri = null, youtubeId = null,
                )) {
                return@mapNotNull null
            }
            // v0.9.16: 30-day TTL on dedup so candidates that failed download
            // or were skipped/blocked previously can re-enter the funnel after
            // a month — keeps the discovery surface fresh.
            val dedupSinceMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            val exists = discoveryQueueDao.existsForRecipeSince(
                recipe.id, cand.artist, cand.title, dedupSinceMs,
            )
            if (exists) null else DiscoveryQueueEntity(
                recipeId = recipe.id,
                artist = cand.artist,
                title = cand.title,
                seedArtist = cand.seedArtist,
            )
        }
        if (taste != null) Log.i(TAG, "'${recipe.name}': your record avoided $avoided of ${similarArtistSuggestions.size} discovery candidates")
        if (toInsert.isNotEmpty()) discoveryQueueDao.insertAllIfNew(toInsert)
    }

    /**
     * Candidate shape for [queueDiscoveryCandidates]. Produced by the
     * refresh worker from Last.fm similar-artist + top-track queries.
     */
    data class DiscoveryCandidate(
        val artist: String,
        val title: String,
        val seedArtist: String,
    )

    private fun String.splitTrim(): List<String> =
        this.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
}
