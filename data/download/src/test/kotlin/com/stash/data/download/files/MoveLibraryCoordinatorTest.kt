package com.stash.data.download.files

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StoragePreference
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The move is exercised end to end on a device (it needs a real SAF
 * provider). What is unit-testable — and what a review asked for — is that
 * it cannot run alongside the reorganize pass in EITHER start order.
 */
class MoveLibraryCoordinatorTest {

    private val context = mockk<Context>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>(relaxed = true)
    private val gate = LibraryRewriteGate()

    private val coordinator = MoveLibraryCoordinator(context, trackDao, storagePreference, gate)

    @Test fun `refuses to start while another pass holds the gate`() = runBlocking {
        assertThat(gate.tryAcquire("reorganize")).isTrue()

        // A mock Uri: the gate is checked before the destination is touched.
        coordinator.start(mockk())
        delay(300)

        assertThat(coordinator.state.value).isInstanceOf(MoveLibraryState.Error::class.java)
        coVerify(exactly = 0) { trackDao.healFilePath(any(), any()) }
    }
}
