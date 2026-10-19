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
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.GenreNormalizer
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.isTransientLastFmFailure
import com.stash.core.data.sync.enqueueUniquePeriodicWorkReviving
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * Takes up to [BATCH_SIZE] artists from [ArtistGenreDao.missingArtists] (most-listened first, no
 * row yet or a stale miss), asks `artist.getTopTags` for each (the client's 7-day response cache,
 * Worker proxy and per-key rate-limit breakers apply), keeps only real genres
 * ([GenreNormalizer.topGenres]) and stores them. Paced at [REQUEST_INTERVAL_MS] like the other
 * Last.fm enrichment workers. Rows are written every [WRITE_EVERY] artists and when the run ends,
 * also when it is stopped, so a long first run keeps what it found.
 *
 * ## What gets written
 *  - genres found: TAGGED, kept for good;
 *  - Last.fm answered and has no genre (no tags, only non-genres, or "artist not found"):
 *    UNTAGGABLE, asked again after [ArtistGenreEntity.UNTAGGABLE_RETRY_MS];
 *  - a request failed (offline, 5xx, 429, every key throttled): nothing at first, since it is
 *    likely an outage, not the artist. Only when a LATER request in the same run succeeds is the
 *    failure known to be the artist's own, and then it is stored as FAILED, retried after
 *    [ArtistGenreEntity.FAILED_RETRY_MS], so one bad name can't head every run forever.
 *    [MAX_CONSECUTIVE_ERRORS] failures in a row end the run (Last.fm or the network is down).
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
                "unsettled=${stats.unsettled}",
        )
        // Nothing answered at all: retry with backoff rather than wait a day.
        return if (stats.settled == 0 && stats.unsettled > 0) Result.retry() else Result.success()
    }

    private class RunStats {
        var tagged = 0
        var untaggable = 0
        var failed = 0
        var unsettled = 0
        val settled: Int get() = tagged + untaggable + failed
    }

    private suspend fun fetchAll(
        artists: List<String>,
        pending: MutableList<ArtistGenreEntity>,
        stats: RunStats,
    ) {
        // Failures since the last success: an outage until a later success proves otherwise.
        val unexplainedFailures = mutableListOf<String>()
        for ((index, artist) in artists.withIndex()) {
            if (isStopped) break
            if (index > 0) pause(REQUEST_INTERVAL_MS)

            val result = lastFmApiClient.getArtistTopTags(artist)
            // isTransientLastFmFailure re-throws a cancellation runCatching captured.
            if (result.isTransientLastFmFailure()) {
                unexplainedFailures += artist
                if (unexplainedFailures.size >= MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "stopping after ${unexplainedFailures.size} failed requests in a row")
                    break
                }
                continue
            }

            val now = clock()
            // Last.fm answered, so the failures before this one were about those artists.
            unexplainedFailures.forEach { pending += row(it, emptyList(), ArtistGenreEntity.STATUS_FAILED, now) }
            stats.failed += unexplainedFailures.size
            unexplainedFailures.clear()

            // A success, or Last.fm's "artist not found": both are answers.
            val genres = GenreNormalizer.topGenres(result.getOrNull().orEmpty())
            if (genres.isEmpty()) {
                pending += row(artist, genres, ArtistGenreEntity.STATUS_UNTAGGABLE, now)
                stats.untaggable++
            } else {
                pending += row(artist, genres, ArtistGenreEntity.STATUS_TAGGED, now)
                stats.tagged++
            }
            if (pending.size >= WRITE_EVERY) flush(pending)
        }
        // Failed without a later success, or not reached (outage, stopped): asked again next run.
        stats.unsettled = artists.size - stats.settled
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
            pending.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed write loses this batch only; those artists stay missing and come back next run.
            Log.w(TAG, "writing ${pending.size} genre rows failed: ${e.message}")
            pending.clear()
        }
    }

    companion object {
        private const val TAG = "ArtistGenres"
        private const val PERIODIC_WORK_NAME = "stash_artist_genres"
        private const val ONE_TIME_WORK_NAME = "stash_artist_genres_now"

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
