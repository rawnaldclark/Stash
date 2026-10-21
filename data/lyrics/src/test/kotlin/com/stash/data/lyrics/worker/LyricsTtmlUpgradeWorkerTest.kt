package com.stash.data.lyrics.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.ManualFetchResult
import com.stash.data.lyrics.RecheckPlan
import com.stash.data.lyrics.TtmlUpgradeResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lyrics runs back off on a 429 or a run of failures (the shared lrc.red/Binimum skip window
 * counts as one), the re-check sweep finishes only when it reached the end, and a run the retired
 * automatic pass queued asks nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LyricsTtmlUpgradeWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A re-check sweep over tracks 1..n, answering [results] in order. */
    private fun recheckRepo(vararg results: TtmlUpgradeResult, total: Int = results.size): LyricsRepository {
        val repo = mockk<LyricsRepository>()
        coEvery { repo.recheckPlan() } returns RecheckPlan(results.indices.map { it + 1L }, total)
        results.forEachIndexed { i, r -> coEvery { repo.recheckTrack(i + 1L) } returns r }
        coEvery { repo.finishRecheck() } just Runs
        return repo
    }

    /** Library Health's Apple-only upgrade over tracks 1..n. */
    private fun manualRepo(vararg results: TtmlUpgradeResult): LyricsRepository {
        val repo = mockk<LyricsRepository>()
        coEvery { repo.trackIdsPendingTtml() } returns results.indices.map { it + 1L }
        results.forEachIndexed { i, r -> coEvery { repo.upgradeToTtml(i + 1L) } returns r }
        return repo
    }

    private var lastResult: ListenableWorker.Result? = null

    private fun runWorker(repo: LyricsRepository, manual: Boolean = false, recheck: Boolean = !manual): ListenableWorker.Result {
        runTest {
            lastResult = TestListenableWorkerBuilder<LyricsTtmlUpgradeWorker>(context)
                .setInputData(
                    workDataOf(LyricsTtmlUpgradeWorker.KEY_MANUAL to manual, LyricsTtmlUpgradeWorker.KEY_RECHECK to recheck),
                )
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(c: Context, name: String, p: WorkerParameters) =
                        LyricsTtmlUpgradeWorker(c, p, repo)
                })
                .build()
                .doWork()
        }
        return lastResult!!
    }

    private fun ListenableWorker.Result.bailed() =
        (this as ListenableWorker.Result.Success).outputData.getBoolean(LyricsTtmlUpgradeWorker.OUT_BAILED, false)

    @Test fun `a run the retired automatic pass queued asks nothing`() {
        val repo = mockk<LyricsRepository>()   // strict: any call fails the test
        val result = runWorker(repo, manual = false, recheck = false)
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test fun `a 429 stops the re-check and keeps its place`() {
        val repo = recheckRepo(TtmlUpgradeResult.UPGRADED, TtmlUpgradeResult.RATE_LIMITED, TtmlUpgradeResult.UPGRADED)
        val result = runWorker(repo)
        assertTrue(result.bailed())
        coVerify(exactly = 0) { repo.recheckTrack(3L) }
        coVerify(exactly = 0) { repo.finishRecheck() }
    }

    @Test fun `the lrc-red skip window staying open stops the re-check`() {
        // PAUSED = the shared lrc.red/Binimum breaker is open: no request was sent.
        val repo = recheckRepo(*Array(7) { TtmlUpgradeResult.PAUSED })
        val result = runWorker(repo)
        assertTrue(result.bailed())
        coVerify(exactly = 1) { repo.recheckTrack(5L) }
        coVerify(exactly = 0) { repo.recheckTrack(6L) }
        coVerify(exactly = 0) { repo.finishRecheck() }
    }

    @Test fun `five failures in a row stop the run`() {
        val repo = recheckRepo(*Array(7) { TtmlUpgradeResult.FAILED })
        val result = runWorker(repo)
        assertTrue(result.bailed())
        coVerify(exactly = 1) { repo.recheckTrack(5L) }
        coVerify(exactly = 0) { repo.recheckTrack(6L) }
        assertEquals(5, (result as ListenableWorker.Result.Success).outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FAILED, -1))
    }

    @Test fun `a success resets the failure streak`() {
        val f = TtmlUpgradeResult.FAILED
        val repo = recheckRepo(f, f, f, f, TtmlUpgradeResult.NO_TTML, f, f, f, f)
        val result = runWorker(repo)
        assertFalse(result.bailed())
        coVerify(exactly = 1) { repo.recheckTrack(9L) }
    }

    @Test fun `a re-check that reaches the end stamps it and counts from where earlier runs got to`() {
        // A resumed sweep: 2 of 10 left, so the count picks up at 8.
        val repo = recheckRepo(TtmlUpgradeResult.UPGRADED, TtmlUpgradeResult.NO_TTML, total = 10)
        val result = runWorker(repo) as ListenableWorker.Result.Success
        assertFalse(result.bailed())
        assertEquals(10, result.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_TOTAL, -1))
        assertEquals(1, result.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_UPGRADED, -1))
        coVerify(exactly = 1) { repo.finishRecheck() }
        coVerify(exactly = 0) { repo.upgradeToTtml(any(), any(), any(), any()) }
    }

    @Test fun `a bail still runs the manual fetch for tracks with no lyrics`() {
        val repo = manualRepo(TtmlUpgradeResult.RATE_LIMITED, TtmlUpgradeResult.UPGRADED)
        coEvery { repo.trackIdsMissingLyrics() } returns listOf(10L, 11L)
        coEvery { repo.fetchLyricsNow(10L) } returns ManualFetchResult.FETCHED
        coEvery { repo.fetchLyricsNow(11L) } returns ManualFetchResult.NOT_FOUND
        val result = runWorker(repo, manual = true)
        assertTrue(result.bailed())
        coVerify(exactly = 0) { repo.upgradeToTtml(2L) }
        coVerify(exactly = 1) { repo.fetchLyricsNow(10L) }
        coVerify(exactly = 1) { repo.fetchLyricsNow(11L) }
        assertEquals(1, (result as ListenableWorker.Result.Success).outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FETCHED, -1))
    }
}
