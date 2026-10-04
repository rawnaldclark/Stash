package com.stash.core.data.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackExistenceRef
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Sync-time file adoption (#77/#163): after a reinstall the DB has track rows
 * but none marked downloaded, while the user's SAF folder still holds every
 * file. The adopt pass existed since #413 but ONLY behind the manual
 * Library Health -> Verify button, so the reinstall flow re-downloaded
 * gigabytes. reconcile() now runs an injected adopter BEFORE the requeue step,
 * so files already on disk are marked downloaded before anything can queue
 * them for re-download.
 */
class LibraryReconciliationAdoptionTest {

    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true) {
        coEvery { deleteOrphanedQueueEntries() } returns 0
        coEvery { resetStaleSyncInProgress() } returns 0
        coEvery { getUnqueuedTrackIds(any()) } returns emptyList()
    }
    private val trackDao: TrackDao = mockk(relaxed = true) {
        coEvery { getDownloadedTrackRefs() } returns emptyList()
    }
    private val tokenManager: TokenManager = mockk(relaxed = true) {
        coEvery { isAuthenticated(any()) } returns false
    }

    private fun useCase() = LibraryReconciliationUseCase(downloadQueueDao, trackDao, tokenManager)

    @Test
    fun `adopter runs before the requeue step`() = runTest {
        var adopted = false
        coEvery { downloadQueueDao.getUnqueuedTrackIds(any()) } answers {
            // The requeue query must only see the world AFTER adoption marked
            // on-disk files as downloaded — otherwise a reinstalled library is
            // queued for a full re-download in the same pass that adopts it.
            assertThat(adopted).isTrue()
            emptyList()
        }

        // A sync's reconcile: only a sync requeues (#532), and the sync's download step
        // (TrackDownloadWorker, the one caller with an adopter) always passes its run.
        useCase().reconcile(syncId = 1L, adoptExistingFiles = { adopted = true; 42 })

        coVerifyOrder {
            trackDao.getDownloadedTrackRefs()
            downloadQueueDao.getUnqueuedTrackIds(any())
        }
        assertThat(adopted).isTrue()
    }

    @Test
    fun `adopted count is reported in the result`() = runTest {
        val result = useCase().reconcile(adoptExistingFiles = { 1337 })
        assertThat(result.filesAdopted).isEqualTo(1337)
    }

    @Test
    fun `default adopter is a no-op`() = runTest {
        val result = useCase().reconcile()
        assertThat(result.filesAdopted).isEqualTo(0)
    }

    @Test
    fun `the missing count is the tracks the reset actually reset`() = runTest {
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            TrackExistenceRef(1L, "Evanescence", "Fallen", "Lacrymosa", "/music/one.opus"),
            TrackExistenceRef(2L, "Evanescence", "Synthesis", "Lacrymosa", "/music/two.opus"),
        )
        // Track 2 moved to a new file between the check and the reset.
        coEvery { trackDao.resetMissingFiles(any(), any()) } returns 1

        val result = useCase().reconcile(checkFileExists = { _, _, _, _, _ -> FileExistenceResult(exists = false) })

        assertThat(result.filesMissing).isEqualTo(1)
    }
}
