package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.dao.LastFmCacheDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.GenreNormalizer
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmTag
import com.stash.core.data.lastfm.LastFmTagsAnswer
import com.stash.core.data.lastfm.isLastFmRateLimited
import com.stash.core.data.sync.enqueueUniquePeriodicWorkReviving
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fills `artist_genres`: the Last.fm genres of every artist in listening history, streamed or
 * downloaded, for Top genres in "Your listening".
 *
 * ## Why per artist
 * The older `track_tags` (TagEnrichmentWorker) covers downloaded songs only — about 15% of what
 * people actually listen to. Genres of the artist cover every listen with one request per artist,
 * and an artist's genre is what a listener means by "the genre" anyway.
 *
 * ## One run
 * Takes up to [BATCH_SIZE] artists from [ArtistGenreDao.missingArtists] (most-listened first: no
 * row yet, or a row past its refresh / retry age), asks `artist.getTopTags` for each (the client's
 * 7-day response cache, Worker proxy and per-key rate-limit breakers apply), keeps only real
 * genres ([GenreNormalizer.topGenres]) and stores them, paced at [REQUEST_INTERVAL_MS] like the
 * other Last.fm enrichment workers. Rows are written every [WRITE_EVERY] artists and when the run
 * ends, also when it is stopped. Expired Last.fm responses are pruned at the end, as
 * SyncFinalizeWorker does, so the cache stays bounded for people who never sync.
 *
 * Only one run at a time in the process ([runLock]): the daily and the on-demand run have their own
 * unique work names and could otherwise overlap and ask for the same artists twice.
 *
 * ## What each answer writes
 *  - genres: TAGGED (fetched again after [ArtistGenreEntity.TAGGED_REFRESH_MS]);
 *  - Last.fm answered with no genre (no tags, only non-genres, error 6 "not found", error 7
 *    "invalid resource"): UNTAGGABLE, asked again after [ArtistGenreEntity.UNTAGGABLE_RETRY_MS];
 *  - rate limited (HTTP 429, error 29, every key's breaker open): nothing. Says nothing about the
 *    artist; left for the next run, and the run asks WorkManager for a retry;
 *  - network failure (an [IOException]: offline, DNS, timeout): nothing — an outage, not the
 *    artist; a run where nothing at all was answered asks for a retry;
 *  - any other failure (HTTP 5xx, another Last.fm error, an unreadable body): FAILED, asked again
 *    after [ArtistGenreEntity.FAILED_RETRY_MS], so an artist whose request keeps failing can't
 *    head the queue and block every run.
 *
 * A refresh never loses genres: when a TAGGED artist's refresh finds no genre it keeps the old
 * ones, and when it fails it keeps them and comes due again after the FAILED retry age.
 *
 * [MAX_CONSECUTIVE_ERRORS] failures in a row end the run. Only an answer fetched over the network
 * counts as proof Last.fm is up and resets that count: cached answers are served even while every
 * key is throttled, so they neither reset nor add to it.
 *
 * Without Last.fm credentials (CI builds, forks without a key) the worker does nothing and
 * succeeds. Reads listening history only: an empty history means nothing to do. The history Pause
 * switch needs no check, as no listen is recorded here.
 */
@HiltWorker
class ArtistGenreWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val artistGenreDao: ArtistGenreDao,
    private val lastFmCacheDao: LastFmCacheDao,
    private val lastFmApiClient: LastFmApiClient,
    private val credentials: LastFmCredentials,
) : CoroutineWorker(appContext, params) {

    /** Clock seam for tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Pacing seam for tests (a real delay in production, so Last.fm sees a polite client). */
    internal var pause: suspend (Long) -> Unit = { delay(it) }

    override suspend fun doWork(): Result {
        if (!credentials.isConfigured) {
            Log.d(TAG, "Last.fm credentials not configured; skipping")
            return Result.success()
        }
        if (!runLock.tryLock()) {
            Log.d(TAG, "another genre run is active; skipping")
            return Result.success()
        }
        return try {
            val result = fetchMissing()
            pruneResponseCache()
            result
        } finally {
            runLock.unlock()
        }
    }

    /** What one request came back with. */
    private sealed interface Outcome {
        /** Last.fm answered (or "not found"); [fromNetwork] = not served from the local cache. */
        data class Answer(val tags: List<LastFmTag>, val fromNetwork: Boolean) : Outcome
        data object RateLimited : Outcome
        data object NetworkDown : Outcome
        data class ArtistFailure(val error: Throwable?) : Outcome
    }

    private class RunStats {
        var tagged = 0
        var untaggable = 0
        var failed = 0
        var rateLimited = 0
        var networkDown = 0
        val answered: Int get() = tagged + untaggable
    }

    private suspend fun fetchMissing(): Result {
        val artists = artistGenreDao.missingArtists(BATCH_SIZE, clock())
        if (artists.isEmpty()) {
            Log.d(TAG, "every listened artist has genres (or no history yet)")
            return Result.success()
        }
        Log.i(TAG, "fetching genres for ${artists.size} artists")

        val pending = mutableListOf<ArtistGenreEntity>()
        val stats = RunStats()
        try {
            fetchAll(artists, pending, stats)
        } finally {
            // Also when the run is stopped: what was found so far is kept.
            withContext(NonCancellable) { flush(pending) }
        }
        Log.i(
            TAG,
            "done: tagged=${stats.tagged} untaggable=${stats.untaggable} failed=${stats.failed} " +
                "rateLimited=${stats.rateLimited} networkDown=${stats.networkDown}",
        )
        return when {
            // Throttled artists were left for later: come back once the breakers have cooled.
            stats.rateLimited > 0 -> Result.retry()
            // Nothing answered at all because the network was down: retry with backoff.
            stats.answered == 0 && stats.failed == 0 && stats.networkDown > 0 -> Result.retry()
            else -> Result.success()
        }
    }

    private suspend fun fetchAll(
        artists: List<String>,
        pending: MutableList<ArtistGenreEntity>,
        stats: RunStats,
    ) {
        var consecutiveFailures = 0
        for ((index, artist) in artists.withIndex()) {
            if (isStopped) break
            if (index > 0) pause(REQUEST_INTERVAL_MS)

            when (val outcome = classify(lastFmApiClient.getArtistTopTagsAnswer(artist))) {
                is Outcome.Answer -> {
                    if (outcome.fromNetwork) consecutiveFailures = 0
                    val genres = GenreNormalizer.topGenres(outcome.tags)
                    if (genres.isEmpty()) {
                        pending += noGenreRow(artist)
                        stats.untaggable++
                    } else {
                        pending += row(artist, genres, ArtistGenreEntity.STATUS_TAGGED, clock())
                        stats.tagged++
                    }
                }
                Outcome.RateLimited -> {
                    stats.rateLimited++
                    consecutiveFailures++
                }
                Outcome.NetworkDown -> {
                    stats.networkDown++
                    consecutiveFailures++
                }
                is Outcome.ArtistFailure -> {
                    Log.w(TAG, "genres for an artist failed: ${outcome.error?.message}")
                    pending += failedRow(artist)
                    stats.failed++
                    consecutiveFailures++
                }
            }
            if (pending.size >= WRITE_EVERY) flush(pending)
            if (consecutiveFailures >= MAX_CONSECUTIVE_ERRORS) {
                Log.w(TAG, "stopping after $consecutiveFailures failed requests in a row")
                break
            }
        }
    }

    /** Sorts a result; rethrows a cancellation the client's runCatching captured. */
    private fun classify(result: kotlin.Result<LastFmTagsAnswer>): Outcome {
        result.getOrNull()?.let { return Outcome.Answer(it.tags, fromNetwork = !it.fromCache) }
        if (result.isLastFmRateLimited()) return Outcome.RateLimited // also rethrows cancellation
        val error = result.exceptionOrNull()
        if (error is LastFmApiException && error.code in FINAL_NO_DATA_ERRORS) {
            // Last.fm itself answered that it has nothing: a network answer.
            return Outcome.Answer(emptyList(), fromNetwork = true)
        }
        if (error is IOException) return Outcome.NetworkDown
        return Outcome.ArtistFailure(error)
    }

    /**
     * No genre found. A refresh of an artist that had genres keeps them (re-stamped, so it is not
     * asked again for another refresh period) rather than wiping them on one empty answer.
     */
    private suspend fun noGenreRow(artist: String): ArtistGenreEntity {
        val now = clock()
        val existing = taggedRow(artist)
        return existing?.copy(fetchedAt = now)
            ?: row(artist, emptyList(), ArtistGenreEntity.STATUS_UNTAGGABLE, now)
    }

    /**
     * The artist's own request failed. An artist that had genres keeps them and comes due again
     * after the FAILED retry age, like any failed artist.
     */
    private suspend fun failedRow(artist: String): ArtistGenreEntity {
        val now = clock()
        val existing = taggedRow(artist)
        return existing?.copy(
            fetchedAt = now - ArtistGenreEntity.TAGGED_REFRESH_MS + ArtistGenreEntity.FAILED_RETRY_MS,
        ) ?: row(artist, emptyList(), ArtistGenreEntity.STATUS_FAILED, now)
    }

    private suspend fun taggedRow(artist: String): ArtistGenreEntity? =
        try {
            artistGenreDao.get(artist)?.takeIf { it.status == ArtistGenreEntity.STATUS_TAGGED }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun row(artist: String, genres: List<WeightedGenre>, status: String, now: Long) =
        ArtistGenreEntity(
            artistName = artist,
            genres = WeightedGenreCodec.encode(genres),
            status = status,
            fetchedAt = now,
        )

    private suspend fun flush(pending: MutableList<ArtistGenreEntity>) {
        if (pending.isEmpty()) return
        try {
            artistGenreDao.upsertAll(pending.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed write loses this batch only; those artists stay missing and come back next run.
            Log.w(TAG, "writing ${pending.size} genre rows failed: ${e.message}")
        } finally {
            pending.clear()
        }
    }

    /** Best effort, never fails the run (same rules as SyncFinalizeWorker). */
    private suspend fun pruneResponseCache() {
        try {
            lastFmCacheDao.prune(clock() - LastFmApiClient.CACHE_TTL_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pruning expired Last.fm responses failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ArtistGenres"
        private const val PERIODIC_WORK_NAME = "stash_artist_genres"
        private const val ONE_TIME_WORK_NAME = "stash_artist_genres_now"

        /** One run at a time per process, whichever unique work started it. */
        internal val runLock = Mutex()

        /** Last.fm errors that mean "nothing to find here": 6 not found, 7 invalid resource. */
        private val FINAL_NO_DATA_ERRORS = setOf(LastFmApiException.NOT_FOUND, LastFmApiException.INVALID_RESOURCE)

        /** Artists per run: a first run over a year of history finishes in a couple of minutes. */
        internal const val BATCH_SIZE = 300

        /** ~4.5 requests a second, as TagEnrichmentWorker: under Last.fm's documented ceiling. */
        internal const val REQUEST_INTERVAL_MS = 220L

        /** Failed requests in a row that end a run. */
        internal const val MAX_CONSECUTIVE_ERRORS = 5

        /** Rows written per transaction while a run is going. */
        internal const val WRITE_EVERY = 50

        /**
         * The daily pass that picks up newly listened artists. Unmetered network and battery not
         * low: it is background work nobody is waiting for. A live schedule keeps its next run
         * time (UPDATE); a finished one is revived.
         */
        suspend fun schedulePeriodic(context: Context) {
            val work = PeriodicWorkRequestBuilder<ArtistGenreWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWorkReviving(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        }

        /**
         * A run now, for when someone opens "Your listening" (phase 2b): any network, since they
         * are looking at the screen. KEEP, so opening the screen again while a run is queued or
         * going changes nothing. The requests are small (a few KB per artist) and most are
         * answered from the 7-day response cache after the first run.
         */
        fun requestNow(context: Context) {
            val work = OneTimeWorkRequestBuilder<ArtistGenreWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                work,
            )
        }
    }
}
