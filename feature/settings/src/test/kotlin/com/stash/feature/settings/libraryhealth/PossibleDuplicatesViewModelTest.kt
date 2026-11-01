package com.stash.feature.settings.libraryhealth

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.library.duplicates.DuplicateCleanupState
import com.stash.core.data.library.duplicates.DuplicateKind
import com.stash.core.data.library.duplicates.DuplicatePair
import com.stash.core.data.library.duplicates.PossibleDuplicates
import com.stash.core.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Possible duplicates' screen logic: a merge that would delete a file asks once first, the row
 * holding the song's only file can't be unticked, and "Not a duplicate" is remembered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PossibleDuplicatesViewModelTest {

    private val duplicates = mockk<PossibleDuplicates>(relaxed = true)
    private val cleanupState = mockk<DuplicateCleanupState> {
        every { lastRun } returns flowOf(null)
    }
    private val musicRepository = mockk<MusicRepository>(relaxed = true)

    @Before fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        PossibleDuplicatesViewModel.io = main
    }

    @After fun tearDown() {
        PossibleDuplicatesViewModel.io = Dispatchers.IO
        Dispatchers.resetMain()
    }

    private fun row(id: Long, file: String? = null) = TrackEntity(
        id = id, title = "Starting Over", artist = "LSD and the Search for God", youtubeId = "video$id",
        filePath = file, isDownloaded = file != null, fileFormat = if (file?.endsWith(".flac") == true) "flac" else "opus",
    )

    private fun vm() = PossibleDuplicatesViewModel(duplicates, cleanupState, musicRepository)

    @Test fun `a merge that removes a file asks first, then merges the row the user ticked`() {
        val pair = DuplicatePair(row(1, "/m/a.flac"), row(2, "/m/b.opus"), DuplicateKind.TWO_FILES)
        coEvery { duplicates.find() } returns listOf(pair)
        coEvery { duplicates.merge(any(), any(), any()) } returns
            PossibleDuplicates.MergeResult.Merged(keptId = 2, removedId = 1, removedFile = true)
        val vm = vm()
        val key = vm.state.value.pairs.single().key

        vm.chooseKeeper(key, 2L)
        vm.merge(key)

        assertEquals("asks before anything is deleted", key, vm.state.value.confirming?.key)
        coVerify(exactly = 0) { duplicates.merge(any(), any(), any()) }

        vm.confirmMerge()

        coVerify(exactly = 1) { duplicates.merge(keeperId = 2L, otherId = 1L, now = any()) }
        coVerify { musicRepository.announceTrackGone(1L) }
        assertNull(vm.state.value.confirming)
        assertEquals(DuplicatesMessage.MERGED_FILE_REMOVED, vm.state.value.message)
    }

    @Test fun `cancel merges nothing`() {
        val pair = DuplicatePair(row(1, "/m/a.flac"), row(2, "/m/b.opus"), DuplicateKind.TWO_FILES)
        coEvery { duplicates.find() } returns listOf(pair)
        val vm = vm()

        vm.merge(vm.state.value.pairs.single().key)
        vm.cancelConfirm()

        coVerify(exactly = 0) { duplicates.merge(any(), any(), any()) }
        assertNull(vm.state.value.confirming)
    }

    @Test fun `a merge with one file merges at once and keeps the downloaded row`() {
        val pair = DuplicatePair(row(1, "/m/a.flac"), row(2), DuplicateKind.TWO_UPLOADS)
        coEvery { duplicates.find() } returns listOf(pair)
        coEvery { duplicates.merge(any(), any(), any()) } returns
            PossibleDuplicates.MergeResult.Merged(keptId = 1, removedId = 2, removedFile = false)
        val vm = vm()
        val key = vm.state.value.pairs.single().key

        vm.chooseKeeper(key, 2L) // the row without the file can't be the one kept
        assertEquals(1L, vm.state.value.pairs.single().keepId)
        vm.merge(key)

        assertNull("nothing to confirm: no file goes", vm.state.value.confirming)
        coVerify(exactly = 1) { duplicates.merge(keeperId = 1L, otherId = 2L, now = any()) }
        coVerify(exactly = 0) { musicRepository.announceTrackGone(any()) }
        assertEquals(DuplicatesMessage.MERGED, vm.state.value.message)
    }

    @Test fun `not a duplicate is remembered and the pair leaves the list`() {
        val pair = DuplicatePair(row(1, "/m/a.flac"), row(2), DuplicateKind.VERSION_WORDS)
        coEvery { duplicates.find() } returnsMany listOf(listOf(pair), emptyList())
        val vm = vm()

        vm.notADuplicate(vm.state.value.pairs.single().key)

        coVerify { duplicates.dismiss(1L, 2L, any()) }
        assertTrue(vm.state.value.pairs.isEmpty())
        assertEquals(DuplicatesMessage.DISMISSED, vm.state.value.message)
    }
}
