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
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.sync.enqueueUniquePeriodicWorkReviving
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "TrackInfoEnrich"
        private const val WORK_NAME = "stash_track_info_enrichment"
        private const val BATCH_SIZE = 200
        private const val REQUEST_INTERVAL_MS = 250L // 4 req/sec — under LFM 5/sec limit

        suspend fun schedulePeriodic(context: Context) {
            val work = PeriodicWorkRequestBuilder<TrackInfoEnrichmentWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
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

        val candidates = trackDao.findTracksNeedingLastfmEnrichment(BATCH_SIZE)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need enrichment — done")
            return Result.success()
        }
        Log.i(TAG, "enriching ${candidates.size} tracks")

        var processed = 0
        for (trackId in candidates) {
            val track = trackDao.getById(trackId) ?: continue
            if (track.artist.isBlank() || track.title.isBlank()) continue

            val info = apiClient
                .getTrackInfo(track.artist, track.title, username = username)
                .getOrNull()
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
        Log.i(TAG, "done: processed=$processed")
        return Result.success()
    }
}
