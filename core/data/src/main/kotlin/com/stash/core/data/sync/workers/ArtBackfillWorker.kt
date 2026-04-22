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
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * One-shot backfill that repairs Discovery-pipeline metadata gaps on
 * already-downloaded tracks. Two independent passes run per invocation:
 *
 * ## Pass 1 — `album_art_url`
 * Fills missing art on is_downloaded=1 tracks with a null `album_art_url`.
 * Strategy per candidate:
 *   - Last.fm `track.getInfo(artist, title)` — album art keyed to the
 *     canonical track identity; preferred because the result is real
 *     album art, not a video still.
 *   - Fallback `https://i.ytimg.com/vi/<id>/hqdefault.jpg` when the row
 *     has a `youtube_id` and Last.fm had nothing.
 *   - Skip when neither source yields anything — leave for a future pass.
 * Rate-limited to one Last.fm call every 220ms.
 *
 * ## Pass 2 — `duration_ms`
 * Fills missing duration on is_downloaded=1 tracks with `duration_ms = 0`.
 * Uses `MediaMetadataRetriever` via [AudioDurationExtractor] to read the
 * authoritative value straight off the container — no network, matches
 * exactly what ExoPlayer sees at play-time. Separate from Pass 1 because
 * not every art-missing row has zero duration (and vice versa), and the
 * duration pass has no network dependency.
 *
 * ## Scope
 * Both passes cover the same root cohort — Stash Discover stubs whose
 * `persistMatchMetadata` couldn't write full metadata because the YT
 * match surfaced no thumbnail, no duration, or tripped the UNIQUE
 * constraint on youtube_id and rolled back the whole UPDATE. See the
 * v0.5.3 and v0.5.4 commit messages for the detailed pipeline trace.
 *
 * ## Scheduling
 * Scheduled once per install from [com.stash.app.StashApplication.onCreate]
 * with `ExistingWorkPolicy.KEEP`, so re-launches become no-ops once the
 * worker completes. Idempotent: if interrupted mid-pass (process killed,
 * Last.fm rate-limited), the remaining rows are picked up by the next
 * scheduled pass because both fill queries are guarded by the still-blank
 * condition.
 */
@HiltWorker
class ArtBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val lastFmApiClient: LastFmApiClient,
    private val credentials: LastFmCredentials,
    private val audioDurationExtractor: AudioDurationExtractor,
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
        runArtPass()
        runDurationPass()
        return Result.success()
    }

    /**
     * Pass 1: repair missing `album_art_url`. Network-bound (Last.fm),
     * rate-limited to respect Last.fm's ceiling. Graceful no-op when no
     * candidates match the query.
     */
    private suspend fun runArtPass() {
        val candidates = trackDao.findArtBackfillCandidates(BATCH_SIZE)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need art backfill")
            return
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
        Log.i(TAG, "art backfill done: filled=$filled skipped=$skipped (no art found)")
    }

    /**
     * Pass 2: repair missing `duration_ms`. Purely local — reads each
     * file's container metadata via `MediaMetadataRetriever`. No rate-
     * limit needed; each extraction takes single-digit milliseconds and
     * the candidate set is typically small (dozens, not thousands).
     */
    private suspend fun runDurationPass() {
        val candidates = trackDao.findDurationBackfillCandidates(BATCH_SIZE)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need duration backfill")
            return
        }
        Log.i(TAG, "backfilling duration for ${candidates.size} tracks")

        var filled = 0
        var skipped = 0
        for (row in candidates) {
            val ms = audioDurationExtractor.extractMs(row.filePath)
            if (ms != null && ms > 0) {
                runCatching { trackDao.fillMissingDuration(row.id, ms) }
                    .onSuccess { filled++ }
                    .onFailure { e ->
                        Log.w(TAG, "fillMissingDuration failed for trackId=${row.id}", e)
                    }
            } else {
                skipped++
            }
        }
        Log.i(TAG, "duration backfill done: filled=$filled skipped=$skipped (unreadable or missing)")
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
