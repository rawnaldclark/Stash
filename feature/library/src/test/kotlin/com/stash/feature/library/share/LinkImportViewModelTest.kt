package com.stash.feature.library.share

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.link.LinkAdded
import com.stash.core.data.link.LinkImporter
import com.stash.core.data.link.LinkKind
import com.stash.core.data.link.LinkLoad
import com.stash.core.data.link.LinkPreview
import com.stash.core.data.link.MusicLinks
import com.stash.core.data.playlistfile.PlaylistFileImport
import com.stash.core.data.playlistfile.PlaylistFileLoad
import com.stash.core.data.playlistfile.PlaylistFileProblem
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.feature.library.R
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Issue #61: a playlist file picked from Library's "+" opens the link's preview and is added as a link's playlist is. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val importer = mockk<LinkImporter>()
    private val files = mockk<PlaylistFileImport>()
    private val document = "content://com.android.providers.downloads.documents/document/msf%3A1000000123"
    private val preview = LinkPreview(
        kind = LinkKind.PLAYLIST,
        name = "Road trip",
        byline = null,
        coverUrl = null,
        service = "",
        songs = listOf(WireSong("Creep", "Radiohead"), WireSong("One More Time", "Daft Punk")),
        sourceId = "custom_link_file_0123456789abcdef01234567",
        fromFile = true,
    )

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(route: Map<String, String> = mapOf("file" to document)) =
        LinkImportViewModel(SavedStateHandle(route), importer, files)

    @Test fun `a playlist file opens its preview, with the playlist an earlier import of it made`() = runTest(dispatcher) {
        coEvery { files.load(document) } returns PlaylistFileLoad.Ok(preview)
        coEvery { importer.existing(preview) } returns 42L
        val vm = vm()
        assertThat(vm.state.value).isEqualTo(LinkImportUiState.Loading)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(LinkImportUiState.Ready(preview, existingId = 42L))
    }

    @Test fun `a file with nothing to read says so, with no retry`() = runTest(dispatcher) {
        coEvery { files.load(document) } returns PlaylistFileLoad.Failed(PlaylistFileProblem.NOTHING_TO_READ)
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value)
            .isEqualTo(LinkImportUiState.Failed(retryable = false, messageRes = R.string.playlist_file_nothing))
    }

    @Test fun `a file that couldn't be opened offers a retry, which reads it again`() = runTest(dispatcher) {
        coEvery { files.load(document) } returnsMany listOf(PlaylistFileLoad.Failed(PlaylistFileProblem.CANT_OPEN), PlaylistFileLoad.Ok(preview))
        coEvery { importer.existing(preview) } returns null
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value)
            .isEqualTo(LinkImportUiState.Failed(retryable = true, messageRes = R.string.playlist_file_cant_open))
        vm.load(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(LinkImportUiState.Ready(preview, existingId = null))
        coVerify(exactly = 2) { files.load(document) }
    }

    @Test fun `Add to library brings the file's songs in through the link import, downloaded when the switch is on`() = runTest(dispatcher) {
        coEvery { files.load(document) } returns PlaylistFileLoad.Ok(preview)
        coEvery { importer.existing(preview) } returns null
        coEvery { importer.add(preview, false, any()) } returns LinkAdded(playlistId = 7L, songsAdded = 2)
        val vm = vm(); advanceUntilIdle()
        vm.setDownload(false)
        vm.add(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(
            LinkImportUiState.Ready(preview, existingId = 7L, download = false, added = LinkAdded(7L, 2)),
        )
        coVerify(exactly = 1) { importer.add(preview, false, any()) }
    }

    @Test fun `a file of one music link opens that link, as if it were pasted, and a retry opens the link again`() = runTest(dispatcher) {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val linked = preview.copy(service = "Spotify", fromFile = false, sourceId = "custom_link_spotify_37i9dQZF1DXcBWIGoYBM5M")
        coEvery { files.load(document) } returns PlaylistFileLoad.Link(url)
        coEvery { importer.load(MusicLinks.find(url)!!) } returns LinkLoad.Ok(linked)
        coEvery { importer.existing(linked) } returns null
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(LinkImportUiState.Ready(linked, existingId = null))
        vm.load(); advanceUntilIdle()
        coVerify(exactly = 1) { files.load(document) }
        coVerify(exactly = 2) { importer.load(any()) }
    }

    @Test fun `a file of several links says to add them one at a time`() = runTest(dispatcher) {
        coEvery { files.load(document) } returns PlaylistFileLoad.Failed(PlaylistFileProblem.LINKS)
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(LinkImportUiState.Failed(retryable = false, messageRes = R.string.playlist_file_links))
    }

    @Test fun `a link's route never reads a file`() = runTest(dispatcher) {
        val vm = vm(mapOf("link" to "not a music link")); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(LinkImportUiState.Failed(LinkImporter.NOT_MUSIC, retryable = false))
        coVerify(exactly = 0) { files.load(any()) }
    }
}
