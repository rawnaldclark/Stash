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
    /** Files found on disk and adopted as already-downloaded (see [LibraryReconciliationUseCase.reconcile]'s `adoptExistingFiles`). */
    val filesAdopted: Int = 0,
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
    /**
     * @param adoptExistingFiles Marks files already present on disk as downloaded
     *   (the #413 adoption pass) and returns how many were adopted. Runs BEFORE the
     *   requeue step so a reinstalled library is recognized instead of queued for a
     *   full re-download in the same pass (#77/#163: users re-downloaded 8 GB after
     *   a reinstall because adoption only lived behind the manual Verify button).
     *   Defaults to a no-op for callers that run adoption themselves
     *   (LibraryHealthViewModel's Verify reports it as its own step).
     */
    suspend fun reconcile(
        syncId: Long? = null,
        onProgress: (step: Int, total: Int) -> Unit = { _, _ -> },
        checkFileExists: suspend (trackId: Long, artist: String, album: String?, title: String, filePath: String) -> com.stash.core.data.library.FileExistenceResult =
            { _, _, _, _, _ -> com.stash.core.data.library.FileExistenceResult(exists = true) },
        adoptExistingFiles: suspend () -> Int = { 0 },
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
            val result = checkFileExists(t.id, t.artist, t.album, t.title, t.filePath)
            when {
                !result.exists -> missingIds.add(t.id)
                result.resolvedFilePath != null -> trackDao.healFilePath(t.id, result.resolvedFilePath)
            }
        }
        if (missingIds.isNotEmpty()) {
            trackDao.resetMissingFiles(missingIds)
        }
        onProgress(4, TOTAL_STEPS)

        // Adopt files already on disk BEFORE the requeue query below: an adopted
        // track flips to is_downloaded and is therefore excluded from requeue.
        // Order is the whole point — reversed, a reinstalled library would be
        // queued for a full re-download in the same pass that recognizes it.
        val adopted = adoptExistingFiles()

        val unqueuedTrackIds = downloadQueueDao.getUnqueuedTrackIds(connectedSources)
        if (unqueuedTrackIds.isNotEmpty()) {
            val newEntries = unqueuedTrackIds.map { trackId ->
                com.stash.core.data.db.entity.DownloadQueueEntity(trackId = trackId, syncId = syncId)
            }
            downloadQueueDao.insertAll(newEntries)
        }
        onProgress(5, TOTAL_STEPS)

        return ReconciliationResult(
            orphansSwept = sweptOrphans,
            staleResumed = resetInProgress,
            filesMissing = missingIds.size,
            unqueuedRequeued = unqueuedTrackIds.size,
            filesAdopted = adopted,
        )
    }
}