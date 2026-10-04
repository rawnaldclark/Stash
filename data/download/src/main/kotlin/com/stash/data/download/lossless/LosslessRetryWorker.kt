package com.stash.data.download.lossless

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadStatus
import com.stash.data.download.lossless.relay.LosslessDownloadPurpose
import kotlinx.coroutines.withContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot sweep over rows in [DownloadStatus.WAITING_FOR_LOSSLESS]. For
 * each, calls the lossless [LosslessSourceRegistry]; on success flips the
 * row to [DownloadStatus.PENDING] so the standard
 * [com.stash.core.data.sync.workers.TrackDownloadWorker] picks it up, on
 * failure leaves the row alone for the next trigger.
 *
 * Does not download — it re-resolves, re-queues, and hands each re-queued
 * row to [SingleTrackDownloadEnqueuer] so it downloads now rather than at
 * the next sync. Never writes COMPLETED or FAILED; the download worker owns
 * that once status is PENDING. That download runs in either mode, so the
 * sweep takes a sync's row only while the song should still download (#532):
 * in Download mode while a playlist the sync downloads still holds it, in
 * Stream-only mode only when the user asked for it. The others keep waiting.
 *
 * Runs as a download ([LosslessDownloadPurpose]): when the relay paces
 * downloads, the sweep stops at that row — every row after it would be paced
 * too — and asks WorkManager to retry later (its backoff grows each time), so
 * a waiting library drains as the day's pool allows.
 *
 * ponytail: a row whose track is genuinely unavailable is re-resolved on every
 * sweep (a Qobuz "locked" answer costs one relay quota each time); add a
 * per-row attempt count if the waiting set grows large.
 *
 * Enqueued by `LosslessRetryScheduler` (Task 9) under a unique work name
 * so multiple triggers within a short window collapse to a single sweep.
 * Uses the standard download Constraints (network mode pref) so it
 * doesn't fire on metered if the user disabled that.
 *
 * Lives in `:data:download` rather than `:core:data` because
 * [LosslessSourceRegistry] is module-local to `:data:download`, and the
 * dependency graph runs `:data:download` -> `:core:data` (not the other
 * way). Putting the worker in `:core:data` would require a circular
 * module dependency.
 */
@HiltWorker
class LosslessRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val registry: LosslessSourceRegistry,
    private val singleTrackDownloadEnqueuer: SingleTrackDownloadEnqueuer,
    private val streamingPreference: StreamingPreference,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Every row resolved here downloads at once, so a sync's row that shouldn't
        // download now is left out before it costs a lookup, still waiting: in Stream-only
        // mode one nobody asked for (#474), in Download mode one whose playlist was
        // switched off.
        val deferred = downloadQueueDao.waitingForLosslessTracks(streamOnly = streamingPreference.current())
        if (deferred.isEmpty()) {
            return Result.success(
                workDataOf(
                    KEY_RESOLVED to 0,
                    KEY_TOTAL to 0,
                ),
            )
        }

        var resolvedCount = 0
        val purpose = LosslessDownloadPurpose()
        for (entry in deferred) {
            val track = trackDao.getById(entry.trackId) ?: continue
            // The user picked this song's audio in Failed Matches (#531). Its
            // download is that video, waiting for Lossy fallback (turning it
            // on requeues every waiting row). A lookup by title would find
            // the recording they rejected, and asked the shared relay again
            // on every trigger, only for the download to wait again.
            if (track.matchPickedAt != null) continue
            // runCatching mirrors DownloadManager's defensive pattern:
            // a single bad source / network blip mustn't halt the whole
            // sweep. Sources are also expected to swallow their own
            // errors and return null, but defense in depth is cheap.
            val match = runCatching {
                withContext(purpose) { registry.resolve(
                    TrackQuery(
                        artist = track.artist,
                        title = track.title,
                        album = track.album.takeIf { it.isNotBlank() },
                        isrc = track.isrc,
                        durationMs = track.durationMs.takeIf { it > 0 },
                        spotifyUri = track.spotifyUri,
                    ),
                ) }
            }.getOrNull()
            if (match != null) {
                downloadQueueDao.updateStatus(
                    id = entry.id,
                    status = DownloadStatus.PENDING,
                )
                singleTrackDownloadEnqueuer.enqueue(entry.id)
                resolvedCount++
            } else if (purpose.pacedRetryAfterSec != null) {
                // The pool is ahead of today's pace: every later row would be paced too.
                return Result.retry()
            }
        }
        return Result.success(
            workDataOf(
                KEY_RESOLVED to resolvedCount,
                KEY_TOTAL to deferred.size,
            ),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "lossless-retry"

        /** Output-data key: how many WAITING_FOR_LOSSLESS rows were flipped to PENDING this sweep. */
        const val KEY_RESOLVED = "lossless_retry_resolved"

        /**
         * Output-data key: how many WAITING_FOR_LOSSLESS rows the sweep took when it started. A
         * sync's rows that shouldn't download now aren't among them (see [doWork]).
         */
        const val KEY_TOTAL = "lossless_retry_total"
    }
}
