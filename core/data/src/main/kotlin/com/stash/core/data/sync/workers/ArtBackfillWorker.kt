package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * One-shot backfill that populates `album_art_url` for already-downloaded
 * tracks whose art is still missing. Two cohorts get repaired:
 *
 *  1. Stash Discover tracks from before v0.5.3 where the match pipeline
 *     surfaced no thumbnail — most common for niche / ambient genres whose
 *     YouTube uploads return plain video renderers instead of music
 *     renderers, so `MatchResult.thumbnailUrl` was null.
 *  2. Any other historical track whose Spotify-side art URL was blank at
 *     sync time and was never retroactively filled.
 *
 * Strategy per candidate:
 *   - Last.fm `track.getInfo(artist, title)` — album-art keyed to the
 *     canonical track identity. Preferred because the result is actual
 *     album art, not a video still.
 *   - Fallback `https://i.ytimg.com/vi/<id>/hqdefault.jpg` when the track
 *     has a `youtube_id` and Last.fm had no result.
 *   - Skip entirely when neither source yields anything — there's nothing
 *     to write and we leave the row for a future backfill pass.
 *
 * Rate-limited to one Last.fm call every 220ms (matches
 * [TagEnrichmentWorker]'s budget, staying comfortably under Last.fm's
 * 5 req/sec ceiling). Runs on any connected network — the payloads are
 * small JSON responses and the total traffic is bounded by the batch cap.
 *
 * Scheduled once per install from [com.stash.app.StashApplication.onCreate]
 * with `ExistingWorkPolicy.KEEP` so re-launches don't re-enqueue a running
 * backfill. The worker is idempotent: if it runs before all candidates are
 * resolved (e.g. user kills the app mid-drain, or Last.fm returns rate-
 * limit errors), the remaining rows are picked up by the next scheduled
 * pass.
 */
@HiltWorker
class ArtBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val lastFmApiClient: LastFmApiClient,
    private val credentials: LastFmCredentials,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ArtBackfill"
        private const val WORK_NAME = "stash_art_backfill"
        private const val BATCH_SIZE = 500
        private const val REQUEST_INTERVAL_MS = 220L

        /**
         * Schedule the one-shot backfill. `KEEP` policy: if a previous run
         * is queued or running, leave it; if it already succeeded this
         * install, don't re-enqueue. Re-launches become no-ops.
         */
        fun enqueueOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<ArtBackfillWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                work,
            )
        }
    }

    override suspend fun doWork(): Result {
        val candidates = trackDao.findArtBackfillCandidates(BATCH_SIZE)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need art backfill")
            return Result.success()
        }
        Log.i(TAG, "backfilling art for ${candidates.size} tracks")

        val lastFmConfigured = credentials.isConfigured
        var filled = 0
        var skipped = 0
        for (row in candidates) {
            val artUrl = resolveArt(row, lastFmConfigured)
            if (artUrl != null) {
                runCatching { trackDao.fillMissingAlbumArtUrl(row.id, artUrl) }
                    .onSuccess { filled++ }
                    .onFailure { e ->
                        Log.w(TAG, "fillMissingAlbumArtUrl failed for trackId=${row.id}", e)
                    }
            } else {
                skipped++
            }
            // Rate limit. Applies after every candidate because the Last.fm
            // path is the common one; for rows where we only hit the YT
            // fallback the delay is a cheap no-op relative to whatever the
            // next row's Last.fm call costs.
            delay(REQUEST_INTERVAL_MS)
        }

        Log.i(TAG, "backfill done: filled=$filled skipped=$skipped (no art found)")
        return Result.success()
    }

    /**
     * Fallback chain for a single candidate. Returns the first non-null
     * result from Last.fm → YT hqdefault, or null if neither yielded a URL.
     */
    private suspend fun resolveArt(
        row: com.stash.core.data.db.dao.ArtBackfillRow,
        lastFmConfigured: Boolean,
    ): String? {
        if (lastFmConfigured) {
            val lfm = runCatching {
                lastFmApiClient.getTrackInfo(row.artist, row.title).getOrNull()
            }.getOrNull()
            if (!lfm.isNullOrBlank()) return lfm
        }
        val vid = row.youtubeId
        if (!vid.isNullOrBlank()) {
            return "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
        }
        return null
    }
}
