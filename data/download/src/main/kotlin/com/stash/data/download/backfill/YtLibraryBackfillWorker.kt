package com.stash.data.download.backfill

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncScheduler
import com.stash.data.download.matching.InnerTubeSearchExecutor
import com.stash.data.download.matching.YtLibraryCanonicalizer
import com.stash.data.ytmusic.model.MusicVideoType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * One-shot worker that retroactively canonicalizes YouTube-library tracks
 * whose stored videoId points at a music-video (OMV) / user-generated
 * (UGC) / podcast upload rather than the Topic-channel audio master
 * (ATV).
 *
 * Without this backfill, [com.stash.data.download.matching.YtLibraryCanonicalizer]
 * only fixes tracks at download-time — any track already on disk before
 * Phase 5 shipped stays as the wrong version until the user manually
 * flags + resyncs it. The worker walks the library and queues those
 * tracks for re-download; the normal [com.stash.core.data.sync.workers.TrackDownloadWorker]
 * chain then picks them up and the canonicalizer runs during
 * [com.stash.data.download.DownloadManager.resolveUrl], swapping
 * OMV → ATV via the same logic Phase 5 validated.
 *
 * ## Scope (MVP)
 * Only checks tracks whose title contains an obvious MV marker
 * (`(Official Video)`, `(Music Video)`, `(MV)`, etc.) via
 * [TrackDao.getYtSourceVideoTitleCandidates]. This is a fast, conservative
 * filter that catches the ~69 clear offenders in a typical library
 * without hitting InnerTube's player endpoint 2000+ times. A future
 * aggressive mode can verify every YT-source track.
 *
 * ## Flow
 * 1. Pull the candidate list from the DAO (~100 tracks typically).
 * 2. For each candidate, throttled to [VERIFY_CONCURRENCY]:
 *    - [InnerTubeSearchExecutor.verifyVideo] to get the live
 *      [MusicVideoType] of the stored videoId.
 *    - If ATV / OFFICIAL_SOURCE_MUSIC / null → skip (title was
 *      misleading; track is actually fine).
 *    - If OMV / UGC / PODCAST_EPISODE → delete the on-disk file,
 *      reset `is_downloaded = 0` + clear file_path, insert a PENDING
 *      download_queue entry.
 * 3. Kick off [SyncScheduler.triggerManualSync] so the full
 *    Fetch → Diff → Download → Finalize chain runs, draining the
 *    new queue entries. The canonicalizer fires per-track during
 *    the download phase, swapping each videoId for its ATV
 *    equivalent.
 *
 * ## Safety
 * Per-track failures (network error, delete failure, whatever) are
 * logged at WARN and don't abort the batch. The user's library
 * survives — worst case, one track stays as the wrong version. The
 * worker can be run again safely: already-canonicalized tracks no
 * longer match the title-pattern filter (because their title is
 * unchanged, but more importantly because their videoId is now ATV
 * so verifyVideo short-circuits to skip).
 */
@HiltWorker
class YtLibraryBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val searchExecutor: InnerTubeSearchExecutor,
    private val syncNotificationManager: SyncNotificationManager,
    private val syncScheduler: SyncScheduler,
    private val canonicalizer: YtLibraryCanonicalizer,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "stash_yt_library_backfill"

        const val KEY_CHECKED = "checked"
        const val KEY_CANONICALIZED = "canonicalized"
        const val KEY_SKIPPED = "skipped"

        private const val TAG = "YtLibBackfill"

        /**
         * Max concurrent [InnerTubeSearchExecutor.verifyVideo] calls.
         * Kept low (4) so we don't earn a 429 on InnerTube's player
         * endpoint mid-pass. Typical backfill size is ~100 tracks, so
         * 4-wide is plenty fast (~25 seconds verify phase).
         */
        private const val VERIFY_CONCURRENCY = 4
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(
            title = "Optimizing YouTube library",
            text = "Checking for wrong-version downloads…",
            progress = -1f,
        )

    override suspend fun doWork(): Result {
        try {
            val candidates = trackDao.getYtSourceVideoTitleCandidates()
            val total = candidates.size
            Log.i(TAG, "Backfill starting: $total candidates with video-title markers")

            if (total == 0) {
                syncNotificationManager.updateProgress(
                    title = "Optimizing YouTube library",
                    text = "Nothing to fix — your library is already clean.",
                    progress = 1f,
                )
                return Result.success(
                    workDataOf(KEY_CHECKED to 0, KEY_CANONICALIZED to 0, KEY_SKIPPED to 0),
                )
            }

            setForeground(
                createForegroundInfo(
                    title = "Optimizing YouTube library",
                    text = "0 of $total checked",
                    progress = 0f,
                ),
            )

            val semaphore = Semaphore(VERIFY_CONCURRENCY)
            val checked = AtomicInteger(0)
            val canonicalized = AtomicInteger(0)
            val skipped = AtomicInteger(0)

            coroutineScope {
                candidates.map { track ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            processCandidate(track)?.let { outcome ->
                                if (outcome == Outcome.QUEUED) canonicalized.incrementAndGet()
                                else skipped.incrementAndGet()
                            } ?: skipped.incrementAndGet()
                        } catch (e: Exception) {
                            Log.w(TAG, "processCandidate failed for trackId=${track.id}", e)
                            skipped.incrementAndGet()
                        } finally {
                            semaphore.release()
                            val done = checked.incrementAndGet()
                            syncNotificationManager.updateProgress(
                                title = "Optimizing YouTube library",
                                text = "$done of $total checked (${canonicalized.get()} queued)",
                                progress = done.toFloat() / total,
                            )
                        }
                    }
                }.awaitAll()
            }

            val finalCanon = canonicalized.get()
            val finalSkipped = skipped.get()
            Log.i(
                TAG,
                "Backfill verify-pass complete: total=$total canonicalized=$finalCanon skipped=$finalSkipped",
            )

            // Hand off to the normal sync chain so the queued tracks get
            // downloaded. The canonicalizer runs per-track during
            // DownloadManager.resolveUrl and swaps videoId to ATV.
            if (finalCanon > 0) {
                syncNotificationManager.updateProgress(
                    title = "Optimizing YouTube library",
                    text = "$finalCanon queued for re-download — starting sync…",
                    progress = 1f,
                )
                syncScheduler.triggerManualSync()
            } else {
                syncNotificationManager.updateProgress(
                    title = "Optimizing YouTube library",
                    text = "Done — no wrong versions found.",
                    progress = 1f,
                )
            }

            return Result.success(
                workDataOf(
                    KEY_CHECKED to total,
                    KEY_CANONICALIZED to finalCanon,
                    KEY_SKIPPED to finalSkipped,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backfill worker failed", e)
            return Result.failure()
        } finally {
            syncNotificationManager.cancelProgress()
        }
    }

    private enum class Outcome { QUEUED, SKIPPED }

    /**
     * Check one candidate and route to the appropriate fix:
     *  - OMV / UGC / PODCAST → full canonicalization (delete file +
     *    re-queue for download; canonicalizer handles title/art/etc.
     *    during the next download pass).
     *  - ATV / OFFICIAL_SOURCE_MUSIC → metadata-only refresh. The audio
     *    is already the canonical version (a prior Phase 6 backfill run
     *    swapped the videoId but didn't update title/album/art yet).
     *    Update the DB fields without re-downloading.
     *  - Unknown (null from verifyVideo) → leave alone.
     *
     * Returns [Outcome.QUEUED] only when a re-download was scheduled.
     * Metadata-only refreshes count as SKIPPED so the sync chain only
     * kicks off if there's actual work for it. Null on verifyVideo
     * failure — caller counts as SKIPPED.
     */
    private suspend fun processCandidate(
        track: com.stash.core.data.db.entity.TrackEntity,
    ): Outcome? {
        val videoId = track.youtubeId ?: return null
        val verification = searchExecutor.verifyVideo(videoId) ?: return Outcome.SKIPPED

        val type = verification.musicVideoType
        if (type == null) {
            Log.d(TAG, "skip: trackId=${track.id} '${track.title}' has no musicVideoType, no fix")
            return Outcome.SKIPPED
        }

        if (type == MusicVideoType.ATV || type == MusicVideoType.OFFICIAL_SOURCE_MUSIC) {
            // Audio is already canonical. If the title still has an MV
            // marker (e.g. '(Official Video)'), the display metadata
            // lagged behind the audio swap — refresh it in-place.
            val hasMvMarker = track.title.contains("Official Video", ignoreCase = true) ||
                track.title.contains("Music Video", ignoreCase = true) ||
                track.title.contains("(Video)", ignoreCase = true) ||
                track.title.contains("(MV)", ignoreCase = true)
            if (hasMvMarker) {
                val refreshed = canonicalizer.refreshMetadata(track.toDomain())
                if (refreshed) {
                    Log.i(TAG, "refreshed metadata: trackId=${track.id} was $type/$videoId")
                }
            }
            return Outcome.SKIPPED
        }

        // Non-canonical upload (OMV/UGC/PODCAST) — delete the file and
        // requeue so the canonicalizer can re-download + fully persist.
        track.filePath?.let { path ->
            try {
                val deleted = File(path).delete()
                Log.d(TAG, "deleted old file path=$path deleted=$deleted")
            } catch (e: Exception) {
                Log.w(TAG, "failed to delete file $path", e)
            }
        }
        trackDao.resetForReDownload(track.id)
        // CRITICAL: youtubeUrl MUST be null. If we pass the old OMV URL
        // here, TrackDownloadWorker forwards it as `preResolvedUrl` to
        // DownloadManager.executeDownload, which bypasses resolveUrl()
        // entirely — so YtLibraryCanonicalizer never fires and we just
        // re-download the exact same OMV audio we deleted above. With
        // null, resolveUrl runs, sees track.youtubeId is still the OMV,
        // and routes through the canonicalizer which swaps to the ATV.
        downloadQueueDao.insert(
            DownloadQueueEntity(
                trackId = track.id,
                syncId = null,
                searchQuery = "${track.artist} - ${track.title}",
                youtubeUrl = null,
            ),
        )
        Log.i(TAG, "queued: trackId=${track.id} '${track.artist} - ${track.title}' ($type/$videoId)")
        return Outcome.QUEUED
    }

    /**
     * Build the foreground-service [ForegroundInfo] used by WorkManager
     * to keep this worker alive while the verify pass runs in the
     * background. Shares the existing "Sync Progress" notification
     * channel so users already recognize the progress indicator style.
     */
    private fun createForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = cancelIntent,
        )
        return ForegroundInfo(
            SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
