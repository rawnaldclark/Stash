package com.stash.core.data.db.dao

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedTrackFilesTest {

    private val trackDao = mockk<TrackDao>()

    @Test
    fun `a file another track records is in use`() = runTest {
        coEvery { trackDao.countOtherTracksWithFilePath("/music/a.flac", 1L) } returns 1
        assertThat(trackDao.fileUsedByAnotherTrack("/music/a.flac", 1L)).isTrue()
    }

    @Test
    fun `a file only this track records is not`() = runTest {
        coEvery { trackDao.countOtherTracksWithFilePath("/music/a.flac", 1L) } returns 0
        assertThat(trackDao.fileUsedByAnotherTrack("/music/a.flac", 1L)).isFalse()
    }

    /** A leftover file is harmless; another song's lost audio is not. */
    @Test
    fun `a failed check keeps the file`() = runTest {
        coEvery { trackDao.countOtherTracksWithFilePath(any(), any()) } throws IllegalStateException("db closed")
        assertThat(trackDao.fileUsedByAnotherTrack("/music/a.flac", 1L)).isTrue()
    }

    @Test
    fun `a cancel is not swallowed`() {
        coEvery { trackDao.countOtherTracksWithFilePath(any(), any()) } throws CancellationException("stop")
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { trackDao.fileUsedByAnotherTrack("/music/a.flac", 1L) }
        }
    }
}
