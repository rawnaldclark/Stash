package com.stash.feature.settings.libraryhealth

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import androidx.lifecycle.viewModelScope
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.TrackBackfillRow
import kotlinx.coroutines.cancel
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
            localFileOps = mockk(relaxed = true),
        )

        vm.restoreMissingDownloads()

        coVerify { music.resumeWaitingDownloads(tap = true) }
    }

    /**
     * #761: a song asked back that no playlist holds would land as a downloaded song in no
     * playlist, which Refresh cleanup deletes once Downloads › Clear history erased its row's
     * mark. The ones no playlist holds join Your Downloads (the repository picks them).
     */
    @Test fun `re-download missing keeps the songs no playlist holds`() {
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
            localFileOps = mockk(relaxed = true),
        )

        vm.restoreMissingDownloads()

        coVerify { music.keepUnlistedInYourDownloads(listOf(1L, 2L)) }
        coVerify { music.resumeWaitingDownloads(tap = true) }
    }

    /**
     * "Refresh quality info", then Back: the blocking metadata read never
     * checked for cancellation and the runCatching around each write swallowed
     * it, so the closed screen kept opening every file of the library.
     */
    @Test fun `closing the screen stops the quality backfill`() {
        val trackDao = mockk<TrackDao>(relaxed = true) {
            coEvery { getRowsNeedingFormatBackfill() } returns
                (1L..3L).map { TrackBackfillRow(id = it, filePath = "/m/$it.flac") }
            coEvery { getRowsNeedingSizeBackfill() } returns
                listOf(TrackBackfillRow(id = 9L, filePath = "/m/9.flac"))
        }
        val extractor = mockk<AudioDurationExtractor>(relaxed = true)
        val vm = LibraryHealthViewModel(
            appContext = mockk(relaxed = true),
            trackDao = trackDao,
            downloadQueueDao = mockk(relaxed = true),
            metadataExtractor = extractor,
            fileExistenceSessionFactory = mockk(relaxed = true),
            reconciliationUseCase = mockk(relaxed = true),
            adoptExistingFilesUseCase = mockk(relaxed = true),
            musicRepository = mockk(relaxed = true),
            localFileOps = mockk(relaxed = true),
        )
        every { extractor.extract(any()) } answers {
            vm.viewModelScope.cancel() // the user leaves while the first file is read
            null
        }

        vm.runBackfill()

        verify(exactly = 1) { extractor.extract(any()) }
        coVerify(exactly = 0) { trackDao.setFileSize(any(), any()) }
    }
}
