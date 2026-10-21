package com.stash.data.download.backfill

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.MetadataEmbedder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * v0.9.35 once-per-version backfill worker that drains the un-tagged
 * library — every downloaded track whose `metadata_embedded_at` is NULL
 * gets the full v0.9.35 tag set (TITLE / ARTIST / ALBUMARTIST / ALBUM /
 * ISRC / ENCODER + cover art) embedded in place via [MetadataEmbedder].
 *
 * ## Loop shape
 * `getTracksNeedingEmbed(limit=50, offset=0)` is called repeatedly. The
 * offset is always 0 — each processed row leaves the
 * `WHERE … metadata_embedded_at IS NULL` result set as soon as
 * [TrackDao.setMetadataEmbeddedAt] fires (with a success timestamp OR
 * the `0L` failure sentinel). So the next batch query naturally returns
 * the next 50 unprocessed rows, and the loop terminates when a batch
 * comes back empty. Cancellation mid-pass leaves whatever's already
 * stamped stamped; the next worker run picks up exactly where this one
 * stopped without any extra bookkeeping.
 *
 * ## Failure handling
 * Three irrecoverable cases all stamp `0L` so the row drops out of the
 * result set and the worker terminates:
 *
 *  - SAF row (`content://…`): we can't open these in place — the SAF
 *    layer is read-only from JVM file APIs. We additionally call
 *    [MetadataBackfillState.incrementSafSkipped] so the Home banner
 *    can surface that detail in the completion summary ("23 of 25
 *    tagged — 2 on SD card couldn't be touched").
 *  - File missing on disk: stamps `0L` but does NOT increment
 *    `safSkipped` (it's a different failure mode the banner doesn't
 *    need to explain).
 *  - [MetadataEmbedder.embedMetadata] threw: same — stamps `0L`.
 *
 * The `0L` sentinel distinguishes "tried and failed" from "never tried"
 * (NULL). A future migration can re-run the backfill on `metadata_embedded_at = 0`
 * rows if the embedder gets fixed, without re-touching the success rows.
 *
 * ## Why `runCatching` around `setMetadataEmbeddedAt(_, 0L)`
 * If the DB write itself throws (extremely unlikely — Room WAL almost
 * never fails on a single-column update), we log and continue. The loop
 * will keep retrying the same row on each batch query, but the outer
 * worker call will eventually time out and `Result.success()` keeps us
 * from re-enqueueing forever. The risk of an infinite loop on a
 * write-broken DB is small enough to accept versus the alternative of
 * propagating the throw and tearing down the whole backfill.
 *
 * ## `total` is a snapshot at worker start
 *
 * `total` is captured once via `observeTracksNeedingEmbedCount().first()`
 * before the loop. If new tracks arrive in the un-tagged set mid-pass
 * (e.g., the user starts syncing during backfill), the banner may render
 * `processed > total` (overshoot) or stall short. This is accepted as
 * cosmetic per spec §10 — re-reading the count each iteration would
 * cause the banner to "jump backward" when new work arrives, which
 * is worse UX. The worker still drains everything to completion
 * because the loop's exit condition is `batch.isEmpty()`, not
 * `processed >= total`.
 */
@HiltWorker
class MetadataBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val metadataEmbedder: MetadataEmbedder,
    private val albumArtCache: AlbumArtCache,
    private val backfillState: MetadataBackfillState,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val total = trackDao.observeTracksNeedingEmbedCount().first()
        backfillState.markStarted(total)

        var processed = 0
        while (true) {
            // OFFSET is always 0: each row leaves the result set via
            // the metadata_embedded_at stamp (success OR 0L failure
            // sentinel). See class KDoc.
            val batch = trackDao.getTracksNeedingEmbed(BATCH_SIZE, offset = 0)
            if (batch.isEmpty()) break
            for (entity in batch) {
                processEntity(entity)
                processed++
                backfillState.publishProgress(processed, total)
            }
        }

        backfillState.markFinished()
        // No lyrics upgrade is chained here any more: that walked the whole library unasked. The
        // user starts one from Settings → Playback → Lyrics → "Re-check library" when they want it.
        return Result.success()
    }

    private suspend fun processEntity(entity: TrackEntity) {
        val track = entity.toDomain()
        val pathString = track.filePath ?: return markFailed(track.id)

        // SAF check MUST precede File() construction. A `content://`
        // URI passed to File(...) gives File.exists() == false, which
        // would collapse SAF rows into the "file missing" branch and
        // break the safSkipped counter the Home banner reads.
        if (pathString.startsWith("content://")) {
            backfillState.incrementSafSkipped()
            return markFailed(track.id)
        }

        val file = File(pathString)
        if (!file.exists()) return markFailed(track.id)

        val art = runCatching { albumArtCache.resolveArt(track) }.getOrNull()
        val embedded = runCatching { metadataEmbedder.embedMetadata(file, track, art) }.isSuccess
        if (embedded) {
            trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis())
        } else {
            markFailed(track.id)
        }
    }

    private suspend fun markFailed(trackId: Long) {
        runCatching { trackDao.setMetadataEmbeddedAt(trackId, 0L) }
            .onFailure { Log.w(TAG, "markFailed update failed for $trackId: ${it.message}") }
    }

    companion object {
        private const val TAG = "MetadataBackfillWorker"
        // Memory per batch ≈ 50 × TrackEntity (small POJOs). Reasonable for
        // libraries up to ~10k tracks; do not raise without re-profiling.
        private const val BATCH_SIZE = 50
        const val UNIQUE_WORK_NAME = "metadata_backfill"
    }
}
