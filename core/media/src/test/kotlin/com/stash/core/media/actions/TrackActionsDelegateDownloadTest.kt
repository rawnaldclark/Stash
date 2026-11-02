package com.stash.core.media.actions

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.TrackItem
import com.stash.data.download.search.SearchDownloadCoordinator
import com.stash.data.download.search.SearchDownloadStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * How Search reports a download's outcome. A song the library already has the audio of is not
 * downloaded again: the row shows the downloaded check and the user is told why nothing happened.
 */
// Same setup as TrackActionsDelegateQueueActionsTest: real flows for the preview player, messages
// collected on backgroundScope (replay = 0), runCurrent() because actions run on backgroundScope.
@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegateDownloadTest {

    private val coordinator: SearchDownloadCoordinator = mockk()

    private val previewPlayer: PreviewPlayer = mockk(relaxed = true) {
        every { previewState } returns MutableStateFlow(PreviewState.Idle)
        every { playerErrors } returns MutableSharedFlow()
    }

    private fun delegate() = TrackActionsDelegate(
        previewPlayer = previewPlayer,
        searchPreviewMediaSource = mockk(relaxed = true),
        previewUrlExtractor = mockk(relaxed = true),
        previewUrlCache = mockk(relaxed = true),
        trackDao = mockk(relaxed = true) { io.mockk.coEvery { findByAlias(any(), any()) } returns null },
        searchDownloadCoordinator = coordinator,
        playerRepository = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        musicRepository = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
    )

    private val item = TrackItem(
        videoId = "dQw4w9WgXcQ", title = "Song", artist = "Artist",
        durationSeconds = 200.0, thumbnailUrl = null,
    )

    @Test
    fun `a song already in the library says so and shows as downloaded`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        every { coordinator.download(item) } returns
            flowOf(SearchDownloadStatus.Resolving, SearchDownloadStatus.AlreadyInLibrary)
        val messages = mutableListOf<String>()
        backgroundScope.launch { d.userMessages.collect { messages.add(it) } }
        runCurrent()

        d.downloadTrack(item)
        runCurrent()
        assertThat(d.downloadedIds.value).contains(item.videoId)
        assertThat(d.downloadingIds.value).doesNotContain(item.videoId)

        advanceTimeBy(1_000)
        runCurrent()
        assertThat(messages).containsExactly("Already in your library")
    }

    @Test
    fun `an album's songs already in the library are told once, with a count`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        val items = (1..3).map { item.copy(videoId = "vid000000$it", title = "Song $it") }
        items.forEach {
            every { coordinator.download(it) } returns
                flowOf(SearchDownloadStatus.Resolving, SearchDownloadStatus.AlreadyInLibrary)
        }
        val messages = mutableListOf<String>()
        backgroundScope.launch { d.userMessages.collect { messages.add(it) } }
        runCurrent()

        items.forEach { d.downloadTrack(it) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(messages).containsExactly("3 songs already in your library")
        assertThat(d.downloadedIds.value).containsAtLeastElementsIn(items.map { it.videoId })
    }

    @Test
    fun `a completed download shows as downloaded without a message`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        every { coordinator.download(item) } returns flowOf(
            SearchDownloadStatus.Resolving,
            SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.LOSSLESS),
            SearchDownloadStatus.Completed,
        )
        val messages = mutableListOf<String>()
        backgroundScope.launch { d.userMessages.collect { messages.add(it) } }
        runCurrent()

        d.downloadTrack(item)
        runCurrent()

        assertThat(messages).isEmpty()
        assertThat(d.downloadedIds.value).contains(item.videoId)
    }
}
