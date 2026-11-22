package com.stash.core.data.audio

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackTuningRow
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.tuning.SongTuning
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [SongTunings]: the stored reading as playback reads it, and "Measure again". */
class SongTuningsTest {

    private val trackDao: TrackDao = mockk(relaxed = true)
    private val measurer: TuningMeasurer = mockk()
    private val tunings = SongTunings(trackDao, measurer)
    private val temp = mutableListOf<File>()

    @After fun cleanup() { temp.forEach { runCatching { it.delete() } } }

    private fun songOnDisk(id: Long): TrackEntity {
        val file = Files.createTempFile("song-tunings-", ".flac").toFile().also { temp += it }
        return TrackEntity(id = id, title = "Song", artist = "Artist", filePath = file.absolutePath)
    }

    @Test fun `it follows the row, one value for each real change`() = runTest(UnconfinedTestDispatcher()) {
        val row = MutableStateFlow<TrackTuningRow?>(null)
        every { trackDao.observeTuning(7L) } returns row
        val seen = mutableListOf<SongTuning>()
        val job = backgroundScope.launch { tunings.observe(7L).toList(seen) }

        row.value = TrackTuningRow(null, null, null)       // in the library, never measured: no change
        row.value = TrackTuningRow(21.7f, 0.9f, 1_000L)
        row.value = TrackTuningRow(21.7f, 0.9f, 2_000L)    // measured again, same reading: no change
        row.value = TrackTuningRow(3f, 0.1f, 3_000L)
        job.cancel()

        assertThat(seen).containsExactly(SongTuning.NotMeasured, SongTuning.Measured(21.7f), SongTuning.NoPitch).inOrder()
    }

    @Test fun `measure again stores the new reading`() = runTest {
        val song = songOnDisk(7L)
        coEvery { trackDao.getById(7L) } returns song
        coEvery { measurer.measure(File(song.filePath!!)) } returns TuningMeasurer.Result.Measured(-4.7f, 1f)

        assertThat(tunings.measureAgain(7L)).isTrue()

        coVerify(exactly = 1) { trackDao.updateTuning(7L, -4.7f, 1f, any()) }
    }

    @Test fun `measure again on a file that can't be decoded stamps it`() = runTest {
        val song = songOnDisk(7L)
        coEvery { trackDao.getById(7L) } returns song
        coEvery { measurer.measure(any()) } returns TuningMeasurer.Result.Failed("bad")

        assertThat(tunings.measureAgain(7L)).isTrue()

        coVerify(exactly = 1) { trackDao.markTuningFailed(7L, any()) }
    }

    @Test fun `a song with no file here isn't measured and keeps what it had`() = runTest {
        coEvery { trackDao.getById(1L) } returns TrackEntity(id = 1L, title = "Stream", artist = "A", filePath = null)
        coEvery { trackDao.getById(2L) } returns TrackEntity(id = 2L, title = "Gone", artist = "A", filePath = "/gone.flac")
        coEvery { trackDao.getById(3L) } returns null

        assertThat(tunings.measureAgain(1L)).isFalse()
        assertThat(tunings.measureAgain(2L)).isFalse()
        assertThat(tunings.measureAgain(3L)).isFalse()

        coVerify(exactly = 0) { measurer.measure(any()) }
        coVerify(exactly = 0) { trackDao.updateTuning(any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.markTuningFailed(any(), any()) }
    }
}
