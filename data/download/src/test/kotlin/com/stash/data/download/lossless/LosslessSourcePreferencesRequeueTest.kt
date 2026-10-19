package com.stash.data.download.lossless

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.DownloadQueueDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the toggle-flip side effects on [LosslessSourcePreferences]:
 *
 *  - `setEnabled` (now the STREAMING Lossless switch) never touches the queue: downloads
 *    wait under the download mode, which it doesn't change.
 *  - `setYoutubeFallbackEnabled(true)` releases all deferred rows.
 *  - `setYoutubeFallbackEnabled(false)` does NOT touch the queue.
 *
 * The deferred state only makes sense under "lossless on + fallback off",
 * so any other configuration must release the queue back to PENDING for
 * the standard yt-dlp worker chain.
 *
 * Robolectric is required because [LosslessSourcePreferences] reads/writes
 * a real `preferencesDataStore` even when we only care about the side
 * effect — mirrors the convention from
 * [LosslessSourcePreferencesYoutubeFallbackTest].
 */
@RunWith(RobolectricTestRunner::class)
class LosslessSourcePreferencesRequeueTest {

    private lateinit var context: Context
    private lateinit var downloadQueueDao: DownloadQueueDao
    private lateinit var prefs: LosslessSourcePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe any prior DataStore state from earlier test runs.
        context.filesDir.resolve("datastore/lossless_source_preferences.preferences_pb")
            .delete()
        // `relaxed = true` (not `relaxUnitFun`) — `requeueWaitingForLossless()`
        // returns Int, not Unit. Tests that care about the call count override
        // the answer with `coEvery { ... } returns N`; tests that assert
        // exactly-zero invocations rely on relaxed defaults to avoid throwing
        // if an unexpected call slips through.
        downloadQueueDao = mockk(relaxed = true)
        prefs = LosslessSourcePreferences(context, downloadQueueDao)
    }

    @Test
    fun `setEnabled false no longer requeues, the switch is streaming only`() = runTest {
        prefs.setEnabled(false)
        coVerify(exactly = 0) { downloadQueueDao.requeueWaitingForLossless() }
    }

    @Test
    fun `setEnabled true does NOT requeue`() = runTest {
        prefs.setEnabled(true)
        coVerify(exactly = 0) { downloadQueueDao.requeueWaitingForLossless() }
    }

    @Test
    fun `setYoutubeFallbackEnabled true requeues all deferred rows`() = runTest {
        coEvery { downloadQueueDao.requeueWaitingForLossless() } returns 5
        prefs.setYoutubeFallbackEnabled(true)
        coVerify { downloadQueueDao.requeueWaitingForLossless() }
    }

    @Test
    fun `setYoutubeFallbackEnabled false does NOT requeue`() = runTest {
        prefs.setYoutubeFallbackEnabled(false)
        coVerify(exactly = 0) { downloadQueueDao.requeueWaitingForLossless() }
    }
}
