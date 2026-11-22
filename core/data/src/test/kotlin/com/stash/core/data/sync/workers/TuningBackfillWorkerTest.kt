package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.audio.TuningMeasurer
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [TuningBackfillWorker], with the DAO and the measurer mocked and real temp files for the paths. */
class TuningBackfillWorkerTest {

    private val trackDao: TrackDao = mockk(relaxed = true)
    private val measurer: TuningMeasurer = mockk()
    private val tempFiles = mutableListOf<File>()

    @After fun cleanup() { tempFiles.forEach { runCatching { it.delete() } } }

    private fun song(id: Long, onDisk: Boolean = true): TrackEntity {
        val path = if (onDisk) Files.createTempFile("tuning-test-", ".flac").toFile().also { tempFiles += it }.absolutePath
        else "/gone/$id.flac"
        return TrackEntity(id = id, title = "Track $id", artist = "Artist", filePath = path)
    }

    private fun worker(stopAfter: Int = Int.MAX_VALUE) = object : TuningBackfillWorker(
        mockk<Context>(relaxed = true), mockk<WorkerParameters>(relaxed = true), trackDao, measurer,
    ) {
        var asked = 0
        override fun shouldStop(): Boolean = asked++ >= stopAfter
    }

    @Test fun `nothing to measure is a success`() = runTest {
        coEvery { trackDao.tracksNeedingTuning(any(), any()) } returns emptyList()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        coVerify(exactly = 0) { measurer.measure(any()) }
    }

    @Test fun `each song's reading is stored, a low confidence included`() = runTest {
        val a = song(1); val b = song(2)
        coEvery { trackDao.tracksNeedingTuning(0L, any()) } returns listOf(a, b)
        coEvery { measurer.measure(File(a.filePath!!)) } returns TuningMeasurer.Result.Measured(-31.8f, 1f)
        coEvery { measurer.measure(File(b.filePath!!)) } returns TuningMeasurer.Result.Measured(12f, 0.05f)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        coVerify(exactly = 1) { trackDao.updateTuning(1L, -31.8f, 1f, any()) }
        coVerify(exactly = 1) { trackDao.updateTuning(2L, 12f, 0.05f, any()) }
        coVerify(exactly = 0) { trackDao.markTuningFailed(any(), any()) }
    }

    @Test fun `a file that can't be decoded is stamped and the run goes on`() = runTest {
        val a = song(1); val b = song(2)
        coEvery { trackDao.tracksNeedingTuning(0L, any()) } returns listOf(a, b)
        coEvery { measurer.measure(File(a.filePath!!)) } returns TuningMeasurer.Result.Failed("bad")
        coEvery { measurer.measure(File(b.filePath!!)) } returns TuningMeasurer.Result.Measured(0f, 1f)

        worker().doWork()

        coVerify(exactly = 1) { trackDao.markTuningFailed(1L, any()) }
        coVerify(exactly = 1) { trackDao.updateTuning(2L, 0f, 1f, any()) }
    }

    @Test fun `a song whose file is gone is left unstamped`() = runTest {
        val gone = song(1, onDisk = false); val here = song(2)
        coEvery { trackDao.tracksNeedingTuning(0L, any()) } returns listOf(gone, here)
        coEvery { measurer.measure(any()) } returns TuningMeasurer.Result.Measured(0f, 1f)

        worker().doWork()

        coVerify(exactly = 1) { measurer.measure(any()) }
        coVerify(exactly = 0) { trackDao.updateTuning(1L, any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.markTuningFailed(1L, any()) }
        coVerify(exactly = 1) { trackDao.updateTuning(2L, any(), any(), any()) }
    }

    @Test fun `a full page of songs it can't open doesn't end the run`() = runTest {
        val first = (1L..250L).map { song(it, onDisk = false) }
        val real = song(251)
        coEvery { trackDao.tracksNeedingTuning(0L, any()) } returns first
        coEvery { trackDao.tracksNeedingTuning(250L, any()) } returns listOf(real)
        coEvery { measurer.measure(any()) } returns TuningMeasurer.Result.Measured(3f, 1f)

        worker().doWork()

        coVerify(exactly = 1) { trackDao.updateTuning(251L, 3f, 1f, any()) }
    }

    @Test fun `stopped after one song, the rest wait for the next run`() = runTest {
        coEvery { trackDao.tracksNeedingTuning(0L, any()) } returns listOf(song(1), song(2), song(3))
        coEvery { measurer.measure(any()) } returns TuningMeasurer.Result.Measured(0f, 1f)

        assertEquals(ListenableWorker.Result.success(), worker(stopAfter = 1).doWork())

        coVerify(exactly = 1) { trackDao.updateTuning(any(), any(), any(), any()) }
    }
}
