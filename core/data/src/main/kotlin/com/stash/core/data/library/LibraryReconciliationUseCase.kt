package com.stash.core.data.library

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.auth.TokenManager
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a reconciliation pass, for the standalone UI's summary line. */
data class ReconciliationResult(
    val orphansSwept: Int,
    val staleResumed: Int,
    val filesMissing: Int,
    val unqueuedRequeued: Int,
)

/**
 * The library-housekeeping pass previously inlined at the top of
 * [com.stash.core.data.sync.workers.TrackDownloadWorker.doWork]: sweeping
 * orphaned queue rows, resetting exhausted/stale retries, verifying that
 * every "downloaded" track's file still exists on disk, and re-queuing
 * undownloaded tracks with no active queue entry.
 *
 * Deliberately does NOT touch [com.stash.data.download.files.FileOrganizer]
 * or [com.stash.data.download.files.LibrarySizeHolder] directly — those
 * types live in a module that depends on :core:data, so reaching for them
 * here would invert the module graph. The disk-existence check is passed
 * in as [checkFileExists] by callers that already have FileOrganizer
 * (TrackDownloadWorker, LibraryHealthViewModel — both feature/worker-layer).
 * Callers should also call `librarySizeHolder.refresh()` themselves right
 * after [reconcile] returns, since a missing-file reset changes storage
 * totals.
 *
 * Extracted so the same pass can run either as the first step of a full
 * sync (chain mode) or standalone from Library & Storage. Every step here
 * is sync-agnostic — none of the underlying queries key off a `syncId`.
 *
 * @param onProgress Invoked after each step with (stepIndex, totalSteps).
 */
@Singleton
class LibraryReconciliationUseCase @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val tokenManager: TokenManager,
) {
    companion object {
        const val TOTAL_STEPS = 5
    }

    /**
     * @param checkFileExists Returns whether the file at a stored
     *   `Track.filePath` still exists. Defaults to "always exists" (skips
     *   the disk check entirely) for callers that don't have file-system
     *   access — currently none, but keeps the signature safe to call
     *   without a lambda if a future caller needs that.
     */
    suspend fun reconcile(
        onProgress: (step: Int, total: Int) -> Unit = { _, _ -> },
        checkFileExists: suspend (artist: String, album: String?, title: String, filePath: String) -> com.stash.core.data.library.FileExistenceResult =
            { _, _, _, _ -> com.stash.core.data.library.FileExistenceResult(exists = true) },
    ): ReconciliationResult {
        onProgress(0, TOTAL_STEPS)

        val connectedSources = buildList {
            if (tokenManager.isAuthenticated(com.stash.core.auth.model.AuthService.SPOTIFY)) add("SPOTIFY")
            if (tokenManager.isAuthenticated(com.stash.core.auth.model.AuthService.YOUTUBE_MUSIC)) add("YOUTUBE")
            add("BOTH")
        }

        val sweptOrphans = downloadQueueDao.deleteOrphanedQueueEntries()
        onProgress(1, TOTAL_STEPS)

        downloadQueueDao.resetExhaustedRetries()
        onProgress(2, TOTAL_STEPS)

        val resetInProgress = downloadQueueDao.resetStaleInProgress()
        onProgress(3, TOTAL_STEPS)

        // Disk-truth check: every track the DB believes is downloaded gets
        // its file_path verified. A track whose file was deleted outside
        // the app has its is_downloaded flag reset here so it's visible to
        // the requeue step immediately below — same pass, not a second run.
        // A track whose file IS present but under a fresh SAF URI (stale
        // cached document id post-reinstall) gets its file_path healed in
        // place instead of being wrongly treated as missing.
        val downloadedTracks = trackDao.getDownloadedTrackRefs()
        val missingIds = mutableListOf<Long>()
        for (t in downloadedTracks) {
            val result = checkFileExists(t.artist, t.album, t.title, t.filePath)
            when {
                !result.exists -> missingIds.add(t.id)
                result.resolvedFilePath != null -> trackDao.healFilePath(t.id, result.resolvedFilePath)
            }
        }
        if (missingIds.isNotEmpty()) {
            trackDao.resetMissingFiles(missingIds)
        }
        onProgress(4, TOTAL_STEPS)

        val unqueuedTrackIds = downloadQueueDao.getUnqueuedTrackIds(connectedSources)
        if (unqueuedTrackIds.isNotEmpty()) {
            val newEntries = unqueuedTrackIds.map { trackId ->
                com.stash.core.data.db.entity.DownloadQueueEntity(trackId = trackId, syncId = null)
            }
            downloadQueueDao.insertAll(newEntries)
        }
        onProgress(5, TOTAL_STEPS)

        return ReconciliationResult(
            orphansSwept = sweptOrphans,
            staleResumed = resetInProgress,
            filesMissing = missingIds.size,
            unqueuedRequeued = unqueuedTrackIds.size,
        )
    }
}