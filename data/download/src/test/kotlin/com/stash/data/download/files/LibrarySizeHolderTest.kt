package com.stash.data.download.files

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LibrarySizeHolderTest {

    @Test
    fun `refresh recomputes and publishes size without a track count change`() = runTest {
        val trackCount = MutableStateFlow(7)
        val initialSize = LibrarySizeBreakdown(100L, 80L, 2)
        val refreshedSize = LibrarySizeBreakdown(140L, 120L, 3)
        val fileOrganizer = mockk<FileOrganizer>()
        val musicRepository = mockk<MusicRepository>()
        every { musicRepository.getTrackCount() } returns trackCount
        coEvery { fileOrganizer.computeMusicLibrarySize() } returnsMany
            listOf(initialSize, refreshedSize)
        val holder = LibrarySizeHolder(fileOrganizer, musicRepository)

        holder.size.test {
            assertThat(awaitItem()).isEqualTo(LibrarySizeBreakdown(0L, 0L, 0))
            assertThat(awaitItem()).isEqualTo(initialSize)

            holder.refresh()

            assertThat(awaitItem()).isEqualTo(refreshedSize)
            coVerify(exactly = 2) { fileOrganizer.computeMusicLibrarySize() }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
