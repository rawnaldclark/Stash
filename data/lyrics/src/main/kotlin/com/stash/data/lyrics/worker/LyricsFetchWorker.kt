package com.stash.data.lyrics.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.SpotifyTrackId  
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Per-track lyrics fetch — runs in two enqueue contexts:
 *
 *  - **Post-download** (from `LyricsFetchTrigger` after a successful download;
 *    wired in Task 10). Unique work name `lyrics_post_download_<trackId>`,
 *    `ExistingWorkPolicy.KEEP`, network constraint, non-expedited.
 *  - **Priority on-open** (from the Now Playing lyrics sheet when a NULL-
 *    stamped track is opened; wired in Task 12). Unique work name
 *    `lyrics_priority_<trackId>`, `ExistingWorkPolicy.REPLACE`, expedited
 *    via `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`.
 *
 * The worker is a thin coroutine wrapper over
 * [LyricsRepository.resolveAndStore]; all source orchestration, sentinel
 * stamping, and sidecar writing happens in the repository (see Task 6/7).
 *
 * **Result contract:**
 *  - Missing/invalid `KEY_TRACK_ID` (enqueuer bug) -> [Result.failure].
 *  - Track deleted between enqueue + run -> [Result.success] no-op.
 *  - `resolveAndStore` returns (hit OR confirmed miss with 0L sentinel) ->
 *    [Result.success].
 *  - `resolveAndStore` throws + `runAttemptCount < MAX_ATTEMPTS` ->
 *    [Result.retry] (WorkManager applies its own backoff).
 *  - `resolveAndStore` throws + `runAttemptCount >= MAX_ATTEMPTS` ->
 *    [Result.success] leaving `tracks.lyrics_fetched_at = NULL`. We do
 *    NOT return [Result.failure] here — the row stays NULL on purpose so
 *    a later on-open priority fetch (or a re-download) can retry it.
 *    Returning `success` matches the actual on-disk state more honestly
 *    (we did not write a 0L sentinel — that's the repository's job on a
 *    *confirmed* miss).
 */
@HiltWorker
class LyricsFetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lyricsRepository: LyricsRepository,
    private val trackDao: TrackDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getLong(KEY_TRACK_ID, -1L)
            .takeIf { it > 0L }
            ?: return Result.failure()
        val track = trackDao.getById(trackId) ?: return Result.success()
        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0L },
            youtubeVideoId = extractYoutubeVideoId(track),
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            spotifyTrackId = SpotifyTrackId.parse(track.spotifyUri),
            // Post-download fetch: a track just downloaded, so the on-demand sources may be asked.
            onDemand = true,
        )
        return try {
            lyricsRepository.resolveAndStore(query)
            Result.success()
        } catch (e: CancellationException) {
            // Must escape before the generic catch: WorkManager cancellation
            // (e.g. a priority REPLACE enqueue for the same track) is not a
            // fetch failure and must not consume a retry attempt.
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    /**
     * YouTube video ID for the InnerTube fallback path
     * ([com.stash.data.lyrics.source.YtMusicLyricsSource]). The column on
     * [TrackEntity] is `youtube_id` (nullable String) — already null when
     * absent, so we just propagate as-is.
     */
    private fun extractYoutubeVideoId(track: TrackEntity): String? = track.youtubeId

    companion object {
        /**
         * Worker input data key for the row id of the track to fetch lyrics for.
         * Stored as a **Long** (`workDataOf(KEY_TRACK_ID to trackIdLong)`); the
         * worker reads via [androidx.work.Data.getLong] with a `-1L` sentinel
         * for absent. T1's amendment 1 corrected `trackId` to `Long` end-to-
         * end so this matches the on-disk schema and the rest of the pipeline.
         */
        const val KEY_TRACK_ID = "track_id"

        /**
         * Max retry attempts before the worker gives up and leaves the row's
         * `lyrics_fetched_at` as NULL for the next backfill pass. WorkManager
         * counts attempts starting from 0, so values 0..2 trigger retries and
         * 3+ exits as success.
         */
        private const val MAX_ATTEMPTS = 3

        /**
         * Unique-work-name prefix for the post-download enqueue path. Combine
         * with the trackId: `"$UNIQUE_PREFIX_POST_DOWNLOAD$trackId"`. Pair
         * with [androidx.work.ExistingWorkPolicy.KEEP] so duplicate enqueues
         * for the same track collapse (a re-download shouldn't kick off a
         * second fetch while the first is still pending).
         */
        const val UNIQUE_PREFIX_POST_DOWNLOAD = "lyrics_post_download_"

        /**
         * Unique-work-name prefix for the priority-on-open enqueue path.
         * Combine with the trackId: `"$UNIQUE_PREFIX_PRIORITY$trackId"`.
         * Pair with [androidx.work.ExistingWorkPolicy.REPLACE] so opening
         * the sheet always jumps the queue with a fresh expedited request,
         * cancelling any in-flight post-download fetch for the same track.
         */
        const val UNIQUE_PREFIX_PRIORITY = "lyrics_priority_"
    }
}
