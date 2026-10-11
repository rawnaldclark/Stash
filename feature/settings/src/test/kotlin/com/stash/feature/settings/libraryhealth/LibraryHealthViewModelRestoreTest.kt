package com.stash.feature.settings.libraryhealth

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Library Health's "Re-download missing" (#474): the rows it queues are started at
 * once, as a tap. It used to enqueue a bare TrackDownloadWorker, which fails at
 * once without a sync, so the tap looked like it did nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryHealthViewModelRestoreTest {

    // The ViewModel's IO work runs on the same test dispatcher as Main, so it all finishes
    // inside the test. On the real Dispatchers.IO, the refresh after the restore resumed onto
    // Main after tearDown's resetMain, an uncaught exception that failed the next test class.
    @Before fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        LibraryHealthViewModel.io = main
    }
    @After fun tearDown() {
        LibraryHealthViewModel.io = Dispatchers.IO
        Dispatchers.resetMain()
    }

    @Test fun `re-download missing starts the download run right away`() {
        val trackDao = mockk<TrackDao>(relaxed = true) {
            coEvery { restorableDownloadIds() } returns listOf(1L, 2L)
        }
        val music = mockk<MusicRepository>(relaxed = true)
        val vm = LibraryHealthViewModel(
            appContext = mockk(relaxed = true),
            trackDao = trackDao,
            downloadQueueDao = mockk(relaxed = true),
            metadataExtractor = mockk(relaxed = true),
            fileExistenceSessionFactory = mockk(relaxed = true),
            reconciliationUseCase = mockk(relaxed = true),
            adoptExistingFilesUseCase = mockk(relaxed = true),
            musicRepository = music,
        )

        vm.restoreMissingDownloads()

        coVerify { music.resumeWaitingDownloads(tap = true) }
    }
}
