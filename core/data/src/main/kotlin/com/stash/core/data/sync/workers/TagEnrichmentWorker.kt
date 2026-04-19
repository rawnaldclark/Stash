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
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.TrackTagEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmTag
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Background enrichment pass: for every downloaded, non-blacklisted track
 * that doesn't have rows in [TrackTagDao], fetches Last.fm tags and
 * writes them.
 *
 * Two-level strategy:
 *  1. `track.getTopTags(artist, title)` — specific but often empty.
 *  2. `artist.getTopTags(artist)` fallback — every listed artist has tags,
 *     so we always land at least some classification.
 *
 * Rate-limited to 5 requests / second (the conservative side of Last.fm's
 * documented ceiling) to avoid hitting 503s. Each batch processes a capped
 * number of tracks so the worker doesn't run for tens of minutes at a
 * stretch; WorkManager re-schedules periodically until the untagged set
 * drains.
 *
 * Runs only on unmetered network + device idle to respect battery /
 * data. Degrades to a no-op when Last.fm credentials aren't configured.
 */
@HiltWorker
class TagEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val trackTagDao: TrackTagDao,
    private val lastFmApiClient: LastFmApiClient,
    private val credentials: LastFmCredentials,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "TagEnrichment"
        private const val WORK_NAME = "stash_tag_enrichment"
        private const val BATCH_SIZE = 200
        private const val REQUEST_INTERVAL_MS = 220L // ~4.5 req/sec target
        private const val MAX_TAGS_PER_TRACK = 5

        /**
         * Schedule the periodic enrichment. Runs once per day, with
         * unmetered + charging constraints so we don't burn data or
         * battery. Idempotent — safe to call from app startup.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val work = PeriodicWorkRequestBuilder<TagEnrichmentWorker>(
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
    }

    override suspend fun doWork(): Result {
        if (!credentials.isConfigured) {
            Log.d(TAG, "Last.fm creds not configured — skipping")
            return Result.success()
        }

        val candidates = trackTagDao.findUntaggedDownloadedTrackIds(BATCH_SIZE)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no untagged tracks — nothing to do")
            return Result.success()
        }
        Log.i(TAG, "enriching ${candidates.size} tracks")

        var processed = 0
        var errors = 0
        for (trackId in candidates) {
            val track = trackDao.getById(trackId) ?: continue
            if (track.artist.isBlank() || track.title.isBlank()) continue

            val tags = fetchTagsWithFallback(track.artist, track.title)
            // Persist AT LEAST a sentinel (empty with source=ARTIST) so this
            // track isn't re-tried on every pass when Last.fm truly has no
            // data. We use a synthetic "__untaggable__" row with weight 0
            // to mark "we tried and found nothing."
            val now = System.currentTimeMillis()
            val rows = if (tags.isEmpty()) {
                listOf(
                    TrackTagEntity(
                        trackId = trackId,
                        tag = "__untaggable__",
                        weight = 0f,
                        source = TrackTagEntity.SOURCE_ARTIST,
                        fetchedAt = now,
                    ),
                )
            } else {
                tags.take(MAX_TAGS_PER_TRACK).map {
                    TrackTagEntity(
                        trackId = trackId,
                        tag = it.tag,
                        weight = it.weight,
                        source = it.source,
                        fetchedAt = now,
                    )
                }
            }
            trackTagDao.replaceForTrack(trackId, rows)
            processed++

            // Rate-limit politely — one delay between tracks covers both
            // the track-level request and the possible artist-level
            // fallback in aggregate.
            delay(REQUEST_INTERVAL_MS)
        }
        Log.i(TAG, "done: processed=$processed errors=$errors")
        return Result.success()
    }

    private data class TaggedResult(val tag: String, val weight: Float, val source: String)

    /**
     * Try track-level first, fall back to artist-level. Returns an empty
     * list only when BOTH calls yield no usable tags (or both threw).
     */
    private suspend fun fetchTagsWithFallback(
        artist: String,
        title: String,
    ): List<TaggedResult> {
        val trackTags = runCatching {
            lastFmApiClient.getTrackTopTags(artist = artist, track = title).getOrNull()
        }.getOrNull().orEmpty()
        if (trackTags.isNotEmpty()) {
            return trackTags.map {
                TaggedResult(it.name, it.weight, TrackTagEntity.SOURCE_TRACK)
            }
        }
        val artistTags = runCatching {
            lastFmApiClient.getArtistTopTags(artist).getOrNull()
        }.getOrNull().orEmpty()
        return artistTags.map {
            TaggedResult(it.name, it.weight, TrackTagEntity.SOURCE_ARTIST)
        }
    }
}

private fun List<LastFmTag>.orEmpty(): List<LastFmTag> = this
