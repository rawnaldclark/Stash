package com.stash.feature.library.allsongs

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.ScannedSongDao
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.model.scannedTrackId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AllSongsViewModelDeleteTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    private val dao = mockk<ScannedSongDao>(relaxed = true)
    private val scanner = mockk<AllSongsScanner>(relaxed = true)
    private val player = mockk<PlayerRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { dao.observeFolders() } returns flowOf(emptyList())
        every { dao.observeSongs() } returns flowOf(emptyList())
        coEvery { dao.getFolders() } returns emptyList()
        every { player.playerState } returns MutableStateFlow(PlayerState())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AllSongsViewModel(
        context = mockk<Context>(relaxed = true),
        dao = dao,
        scanner = scanner,
        player = player,
        covers = ScannedCoverStore(File(tmp.root, "art")),
    )

    private fun track(n: Long) = Track(id = scannedTrackId(n), title = "t$n", artist = "a", filePath = "content://s/$n")

    @Test
    fun `deleted and already-gone files lose their rows and leave the queue, failed ones stay`() = runTest(dispatcher) {
        val deleted = track(1)
        val gone = track(2)
        val failed = track(3)
        every { scanner.deleteFile("content://s/1") } returns FileDeletion.DELETED
        every { scanner.deleteFile("content://s/2") } returns FileDeletion.GONE
        every { scanner.deleteFile("content://s/3") } returns FileDeletion.FAILED
        val vm = viewModel()

        vm.deleteSongs(listOf(deleted, gone, failed))

        coVerify { dao.deleteByUrisAnyFolder(listOf("content://s/1", "content://s/2")) }
        verify { player.evictFromQueue(setOf(deleted.id, gone.id)) }
        assertThat(vm.status.value).contains("Deleted 2")
        assertThat(vm.status.value).contains("Couldn't delete 1")
    }

    @Test
    fun `nothing is evicted or removed when no file could be deleted`() = runTest(dispatcher) {
        every { scanner.deleteFile(any()) } returns FileDeletion.FAILED
        val vm = viewModel()

        vm.deleteSongs(listOf(track(1)))

        coVerify(exactly = 0) { dao.deleteByUrisAnyFolder(match { it.isNotEmpty() }) }
        assertThat(vm.status.value).contains("Couldn't delete 1")
    }
}