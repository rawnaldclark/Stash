package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmEnrichmentBackoff
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.lastfm.isTransientLastFmFailure
import com.stash.core.data.prefs.DataSaverSnapshot
import com.stash.core.data.sync.enqueueUniquePeriodicWorkReviving
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * v0.9.16: Per-track Last.fm enrichment worker. For each downloaded
 * track that hasn't been enriched yet, calls
 * [LastFmApiClient.getTrackInfo] with the user's username and
 * persists mbid, listeners, userPlaycount, userLoved into the
 * `tracks` table. Runs in batches with a polite ~4 req/sec cap to
 * stay under Last.fm's 5 req/sec limit.
 *
 * Modeled on [TagEnrichmentWorker]: same daily periodic + one-shot
 * pattern, same batched loop, same "process this batch then wait
 * for the next periodic fire" behavior. The DAO predicate
 * inherently filters already-processed rows, so resuming after
 * interruption is trivial.
 */
@HiltWorker
class TrackInfoEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val apiClient: LastFmApiClient,
    private val credentials: LastFmCredentials,
    private val sessionPreference: LastFmSessionPreference,
    private val backoff: LastFmEnrichmentBackoff,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "TrackInfoEnrich"
        private const val WORK_NAME = "stash_track_info_enrichment"
        private const val BATCH_SIZE = 200
        private const val REQUEST_INTERVAL_MS = 250L // 4 req/sec — under LFM 5/sec limit

        /** Transient failures in a row that end the batch early (Last.fm or the network is down). */
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** The daily pass: battery not low, any connection; Wi-Fi while Data saver is on. */
        internal fun periodicConstraints(dataSaverOn: Boolean): Constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(backgroundNetworkType(dataSaverOn))
            .build()

        /**
         * The first-launch pass (queued by the app at start, so a fresh install doesn't wait a day):
         * no constraint, as before, except Wi-Fi while Data saver is on.
         */
        fun firstRunConstraints(dataSaverOn: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(backgroundNetworkType(dataSaverOn, otherwise = NetworkType.NOT_REQUIRED))
            .build()

        suspend fun schedulePeriodic(context: Context) {
            val work = PeriodicWorkRequestBuilder<TrackInfoEnrichmentWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(periodicConstraints(DataSaverSnapshot.isOn(context)))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWorkReviving(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!credentials.isConfigured) {
            Log.d(TAG, "Last.fm creds not configured — skipping")
            return Result.success()
        }
        val session = sessionPreference.session.first()
        val username = session?.username
        if (username.isNullOrBlank()) {
            Log.d(TAG, "Last.fm not connected — skipping")
            return Result.success()
        }

        // Tracks a recent run failed on wait their turn (LastFmEnrichmentBackoff),
        // as in TagEnrichmentWorker: the same failing newest tracks headed and
        // ended every run, so older tracks never got their play counts.
        val startedAt = System.currentTimeMillis()
        val candidates = backoff.candidates(LastFmEnrichmentBackoff.Pass.TRACK_INFO, BATCH_SIZE, startedAt) { limit ->
            trackDao.findTracksNeedingLastfmEnrichment(limit)
        }
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need enrichment — done")
            return Result.success()
        }
        Log.i(TAG, "enriching ${candidates.size} tracks")

        val failed = mutableListOf<Long>()
        val processed = try {
            enrich(candidates, username, failed)
        } finally {
            // Also when the run is stopped: the failures so far still count.
            withContext(NonCancellable) {
                backoff.recordFailures(LastFmEnrichmentBackoff.Pass.TRACK_INFO, failed, startedAt)
            }
        }
        Log.i(TAG, "done: processed=$processed errors=${failed.size}")
        return Result.success()
    }

    /**
     * Enriches each of [candidates] for [username], adding the ones Last.fm
     * couldn't be asked about to [failed]. Returns how many got real data.
     */
    private suspend fun enrich(candidates: List<Long>, username: String, failed: MutableList<Long>): Int {
        var processed = 0
        var consecutiveErrors = 0
        for (trackId in candidates) {
            val track = trackDao.getById(trackId) ?: continue
            if (track.artist.isBlank() || track.title.isBlank()) continue

            val result = apiClient.getTrackInfo(track.artist, track.title, username = username)
            // isTransientLastFmFailure also rethrows a captured cancellation.
            if (result.isTransientLastFmFailure()) {
                // Network drop, 5xx, 429 or every key throttled: write NOTHING.
                // The "no data" sentinel below is permanent, and stamping it on
                // an outage zeroed this track's playcount for good. Only
                // Last.fm's own "not found" answer is allowed to reach it. The
                // track backs off so the next run starts elsewhere.
                failed += trackId
                if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "stopping early after $consecutiveErrors consecutive Last.fm failures")
                    break
                }
                delay(REQUEST_INTERVAL_MS)
                continue
            }
            consecutiveErrors = 0
            val info = result.getOrNull()
            if (info != null) {
                trackDao.setLastfmEnrichment(
                    trackId = trackId,
                    mbid = info.mbid,
                    userPlaycount = info.userPlaycount,
                    listeners = info.listeners.takeIf { it > 0 },
                    userLoved = info.userLoved == true,
                )
                processed++
            } else {
                // Mark as processed-with-no-data so we don't retry every
                // run. Use sentinel: userPlaycount = 0, mbid = empty
                // string. (Empty string != null; the DAO predicate at
                // findTracksNeedingLastfmEnrichment checks NULL, so
                // sentinels won't be re-picked.)
                trackDao.setLastfmEnrichment(
                    trackId = trackId,
                    mbid = "",
                    userPlaycount = 0,
                    listeners = null,
                    userLoved = false,
                )
            }
            delay(REQUEST_INTERVAL_MS)
        }
        return processed
    }
}
