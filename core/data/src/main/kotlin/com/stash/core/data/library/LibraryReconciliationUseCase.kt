package com.stash.core.data.library

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.auth.TokenManager
import com.stash.data.download.files.LibrarySizeHolder
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a reconciliation pass, for the standalone UI's summary line. */
data class ReconciliationResult(
    val orphansSwept: Int,
    val staleResumed: Int,
    val unqueuedRequeued: Int,
)

/**
 * The library-housekeeping pass previously inlined at the top of
 * [com.stash.core.data.sync.workers.TrackDownloadWorker.doWork]: sweeping
 * orphaned queue rows, resetting exhausted/stale retries, and re-queuing
 * undownloaded tracks with no active queue entry — then refreshing the
 * disk-truth size stats.
 *
 * Extracted so the same pass can run either as the first step of a full
 * sync (chain mode) or standalone from Library & Storage. Every step here
 * is sync-agnostic — none of the underlying queries key off a `syncId`.
 *
 * @param onProgress Invoked after each step with (stepIndex, totalSteps).
 *   Callers decide where that goes — chain mode feeds it to
 *   [com.stash.core.data.sync.SyncStateManager.onVerifyingLibrary];
 *   standalone mode feeds it to [LibraryVerificationStateManager].
 */
@Singleton
class LibraryReconciliationUseCase @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
    private val tokenManager: TokenManager,
    private val librarySizeHolder: LibrarySizeHolder,
) {
    companion object {
        const val TOTAL_STEPS = 5
    }

    suspend fun reconcile(onProgress: (step: Int, total: Int) -> Unit = { _, _ -> }): ReconciliationResult {
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

        val unqueuedTrackIds = downloadQueueDao.getUnqueuedTrackIds(connectedSources)
        if (unqueuedTrackIds.isNotEmpty()) {
            val newEntries = unqueuedTrackIds.map { trackId ->
                com.stash.core.data.db.entity.DownloadQueueEntity(trackId = trackId, syncId = null)
            }
            downloadQueueDao.insertAll(newEntries)
        }
        onProgress(4, TOTAL_STEPS)

        // Recompute disk-truth size/FLAC stats so a standalone "Verify" run
        // also answers "rebuild my stats" — same flow the Sync screen reads.
        librarySizeHolder.refresh()
        onProgress(5, TOTAL_STEPS)

        return ReconciliationResult(
            orphansSwept = sweptOrphans,
            staleResumed = resetInProgress,
            unqueuedRequeued = unqueuedTrackIds.size,
        )
    }
}