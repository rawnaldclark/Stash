package com.stash.core.data.mix

import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.random.Random

/**
 * Pure materializer for a [StashMixRecipeEntity] — given the recipe and
 * read access to the library, it produces an ordered list of `TrackEntity`
 * rows that the refresh worker writes into the recipe's playlist.
 *
 * ## Scoring model
 *
 * Every candidate track is assigned a score in roughly the 0..2 range
 * that combines three signals, weighted per recipe:
 *
 *  - **Affinity**: how much has the user listened to this track / artist
 *    in the last 180 days? Log-scaled so one extreme favorite doesn't
 *    dominate. The recipe's [StashMixRecipeEntity.affinityBias] shifts
 *    how heavily affinity drives placement:
 *      - positive bias (+) → heavy rotation favorites bubble up
 *      - negative bias (−) → Rediscovery surfaces tracks you *have* liked
 *        but haven't played recently
 *
 *  - **Tag match**: for tag-based recipes (Focus, Late Night, …) this is
 *    the sum of `track_tags.weight` for the intersection of the track's
 *    tags with the recipe's include-tags. Recipes with no include-tags
 *    get a flat tag match of 1.0 (pure affinity + freshness).
 *
 *  - **Freshness boost**: binary 0/1 flag for "hasn't been played within
 *    [StashMixRecipeEntity.freshnessWindowDays] days." Freshness is
 *    worthless for Heavy Rotation (freshness_window = 0 → always boosted
 *    → boost cancels) and critical for Rediscovery.
 *
 * A small jitter is added at sort time so the same score doesn't produce
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
) {

    companion object {
        private const val AFFINITY_WINDOW_MS = 180L * 24 * 60 * 60 * 1000 // 180 days
        private const val BASE_AFFINITY_WEIGHT = 0.5f
        private const val BASE_TAG_WEIGHT = 0.3f
        private const val BASE_FRESHNESS_WEIGHT = 0.2f
        private const val SORT_JITTER = 0.05f
    }

    /**
     * Produces the finalized track list for [recipe]. The worker calling
     * this replaces the playlist_tracks rows for [recipe.playlistId] with
     * this ordering.
     */
    suspend fun generate(recipe: StashMixRecipeEntity): List<TrackEntity> {
        // Step 1: candidate pool — start from every downloaded,
        // non-blacklisted track in the library.
        val rawPool = trackDao.getAllDownloadedNonBlacklisted()

        // Step 2: era filter (cheap, done in-memory).
        var pool = filterByEra(rawPool, recipe)

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

        if (pool.isEmpty()) return emptyList()

        // Step 6: score + sort.
        val affinityMap = buildAffinityMap(pool)
        val tagMatchMap = buildTagMatchMap(pool, includeTags)

        val weightAffinity = BASE_AFFINITY_WEIGHT + recipe.affinityBias * 0.3f
        val weightTag = BASE_TAG_WEIGHT
        val weightFresh = BASE_FRESHNESS_WEIGHT - recipe.affinityBias * 0.15f

        val scored = pool.map { track ->
            val affinity = affinityMap[track.id] ?: 0f
            val tagMatch = tagMatchMap[track.id] ?: 1f
            val fresh = 1f // already filtered, so every track passed the window
            val score = affinity * weightAffinity +
                tagMatch * weightTag +
                fresh * weightFresh +
                Random.nextFloat() * SORT_JITTER
            track to score
        }

        // Step 7: library slots = targetLength * (1 - discoveryRatio), but
        // if we ran out of library candidates we fill up to targetLength.
        // Avoid back-to-back same-artist within the first stretch.
        val librarySlots = (recipe.targetLength * (1f - recipe.discoveryRatio)).toInt()
            .coerceAtLeast(0)
        val ordered = scored.sortedByDescending { it.second }.map { it.first }

        val picked = pickWithArtistSpread(
            candidates = ordered,
            desired = librarySlots.coerceAtMost(pool.size),
        )

        // Step 8: if we couldn't fill library slots AND have no discovery,
        // top up from the remaining pool so we don't deliver a half-empty
        // mix. Better to repeat a little than to look broken.
        val shortfall = recipe.targetLength - picked.size
        if (shortfall > 0 && picked.size < pool.size) {
            val extra = ordered.filter { it !in picked }.take(shortfall)
            return picked + extra
        }

        return picked
    }

    /**
     * Build per-track affinity scores in the range [0, 1]. Counts plays
     * in the last 180 days (from [ListeningEventDao.getPlayCountsSince])
     * and log-scales by the library's max count. Zero plays yields zero
     * affinity; the single most-played track yields 1.0.
     */
    private suspend fun buildAffinityMap(pool: List<TrackEntity>): Map<Long, Float> {
        val since = System.currentTimeMillis() - AFFINITY_WINDOW_MS
        val rows = listeningEventDao.getPlayCountsSince(since)
        if (rows.isEmpty()) return emptyMap()
        val byId = rows.associate { it.trackId to it.plays }
        val max = rows.maxOf { it.plays }.coerceAtLeast(1)
        val poolIds = pool.mapTo(HashSet(pool.size)) { it.id }
        return byId
            .filterKeys { it in poolIds }
            .mapValues { (_, plays) ->
                (ln(1f + plays.toFloat()) / ln(1f + max.toFloat())).coerceIn(0f, 1f)
            }
    }

    /**
     * For each candidate track, sum the tag weights that match the recipe's
     * include-tags. Missing from the map means "no match" → caller defaults
     * the score to 1.0 when the recipe has no include-tags at all.
     */
    private suspend fun buildTagMatchMap(
        pool: List<TrackEntity>,
        includeTags: List<String>,
    ): Map<Long, Float> {
        if (includeTags.isEmpty()) return emptyMap()
        val includeSet = includeTags.mapTo(HashSet(includeTags.size)) { it.lowercase() }
        val result = HashMap<Long, Float>(pool.size)
        for (track in pool) {
            val tags = trackTagDao.getByTrack(track.id)
            val sum = tags
                .filter { it.tag.lowercase() in includeSet }
                .sumOf { it.weight.toDouble() }
                .toFloat()
            if (sum > 0f) result[track.id] = sum
        }
        return result
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

    private fun filterByEra(
        pool: List<TrackEntity>,
        recipe: StashMixRecipeEntity,
    ): List<TrackEntity> {
        if (recipe.eraStartYear == null && recipe.eraEndYear == null) return pool
        // TrackEntity has no direct `year`. Best available proxy is the
        // `date_added` Instant for now; a real year column would require
        // another migration and tag-scanner work. For v0.4.0 we treat
        // era_{start,end} as date_added year, which is approximate but
        // matches "Throwback" (library tracks older than N years) well
        // enough. Tag-based recipes (90s Alternative) should rely on tags
        // rather than era for accurate results.
        val startYear = recipe.eraStartYear
        val endYear = recipe.eraEndYear
        return pool.filter { track ->
            val year = track.dateAdded.atZone(java.time.ZoneId.systemDefault()).year
            (startYear == null || year >= startYear) &&
                (endYear == null || year <= endYear)
        }
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
    ) {
        if (similarArtistSuggestions.isEmpty()) return
        val toInsert = similarArtistSuggestions.mapNotNull { cand ->
            val exists = discoveryQueueDao.existsForRecipe(recipe.id, cand.artist, cand.title)
            if (exists) null else DiscoveryQueueEntity(
                recipeId = recipe.id,
                artist = cand.artist,
                title = cand.title,
                seedArtist = cand.seedArtist,
            )
        }
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
