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
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.StashMixDefaults
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.TimeUnit

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
    private val mixGenerator: MixGenerator,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmCredentials: LastFmCredentials,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "StashMixRefresh"
        private const val WORK_NAME = "stash_mix_refresh"
        private const val ONE_SHOT_WORK_NAME = "stash_mix_refresh_oneshot"
        private const val TOP_ARTISTS_LIMIT = 8
        private const val SIMILAR_PER_SEED = 5
        private const val TRACKS_PER_SIMILAR = 3
        private const val SIMILAR_REQUEST_INTERVAL_MS = 220L
        private const val AFFINITY_LOOKBACK_DAYS = 180L

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
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }

        /**
         * Fire a one-shot refresh immediately — used on first app launch
         * after seeding defaults so users see populated mixes without
         * waiting 24 hours for the periodic schedule, and by the
         * manual-refresh button on the Home Stash Mixes card.
         */
        fun enqueueOneTime(context: Context) {
            val work = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
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

        val active = recipeDao.getActive()
        if (active.isEmpty()) {
            Log.d(TAG, "no active recipes")
            return Result.success()
        }
        Log.i(TAG, "refreshing ${active.size} Stash Mixes")

        val now = System.currentTimeMillis()
        val lastFmConfigured = lastFmCredentials.isConfigured

        for (recipe in active) {
            val tracks = mixGenerator.generate(recipe)
            if (tracks.isEmpty()) {
                Log.d(TAG, "'${recipe.name}' produced 0 tracks — skipping materialize")
                continue
            }

            val playlistId = materializeMix(recipe, tracks, now)
            recipeDao.setPlaylistId(recipe.id, playlistId)
            recipeDao.setLastRefreshedAt(recipe.id, now)

            // Discovery — opportunistic. Don't block refresh success on it.
            if (recipe.discoveryRatio > 0f && lastFmConfigured) {
                runCatching { queueDiscoveryForRecipe(recipe) }
                    .onFailure { Log.w(TAG, "discovery queueing failed for '${recipe.name}'", it) }
            }
        }
        return Result.success()
    }

    /**
     * Find-or-create a playlist row for this recipe, then replace its
     * tracklist with [tracks] in correct order. Returns the playlist_id.
     */
    private suspend fun materializeMix(
        recipe: StashMixRecipeEntity,
        tracks: List<TrackEntity>,
        now: Long,
    ): Long {
        // Existing playlist: verify it's still there (could have been
        // deleted by the user). If gone, fall through to re-create.
        val existing = recipe.playlistId?.let { playlistDao.getById(it) }

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

        // Rebuild track membership in generator order.
        val nowInstant = Instant.ofEpochMilli(now)
        tracks.forEachIndexed { position, track ->
            playlistDao.insertCrossRef(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = track.id,
                    position = position,
                    addedAt = nowInstant,
                )
            )
        }

        // Refresh cover tiles from the first 2 unique album arts.
        val tiles = tracks.mapNotNull { it.albumArtUrl }.distinct().take(2)
        val coverUrl = when {
            tiles.size >= 2 -> tiles.joinToString("|")
            tiles.size == 1 -> tiles[0]
            else -> null
        }
        if (coverUrl != null) {
            playlistDao.updateArtUrl(playlistId, coverUrl)
        }
        playlistDao.updateTrackCount(playlistId, tracks.size)
        return playlistId
    }

    /**
     * For each of the user's top artists (scoped by affinity window),
     * fetch Last.fm similar artists, then for each similar artist its
     * top tracks. Enqueue unique candidates into [discoveryQueueDao].
     *
     * Rate-limited at ~4.5 req/sec on similar-artist calls to stay
     * comfortably inside Last.fm's ceiling.
     */
    private suspend fun queueDiscoveryForRecipe(recipe: StashMixRecipeEntity) {
        val since = System.currentTimeMillis() -
            AFFINITY_LOOKBACK_DAYS * 24 * 60 * 60 * 1000
        val topArtists = listeningEventDao
            .getTopArtistsSince(since, TOP_ARTISTS_LIMIT)
            .map { it.artist }

        if (topArtists.isEmpty()) {
            Log.d(TAG, "'${recipe.name}': no top artists yet — skipping discovery")
            return
        }

        val candidates = mutableListOf<MixGenerator.DiscoveryCandidate>()
        for (seed in topArtists) {
            val similar = runCatching {
                lastFmApiClient.getSimilarArtists(seed, limit = SIMILAR_PER_SEED).getOrNull()
            }.getOrNull().orEmpty()
            for (sim in similar) {
                val top = runCatching {
                    lastFmApiClient.getArtistTopTracks(sim.name, limit = TRACKS_PER_SIMILAR).getOrNull()
                }.getOrNull().orEmpty()
                top.forEach { t ->
                    candidates += MixGenerator.DiscoveryCandidate(
                        artist = t.artist,
                        title = t.title,
                        seedArtist = seed,
                    )
                }
                delay(SIMILAR_REQUEST_INTERVAL_MS)
            }
        }
        if (candidates.isEmpty()) return
        Log.i(TAG, "'${recipe.name}': queueing ${candidates.size} discovery candidates")
        mixGenerator.queueDiscoveryCandidates(recipe, candidates)
    }
}
