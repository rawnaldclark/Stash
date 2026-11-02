package com.stash.core.media.actions

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

// Mirrors TrackActionsDelegateQueueActionsTest's setup: real flows for
// previewState/playerErrors (a relaxed collect throws and kills the scope),
// and runCurrent() not advanceUntilIdle() because actions run on backgroundScope.
@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegatePlaylistTest {

    private val musicRepository: com.stash.core.data.repository.MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard = mockk(relaxed = true)
    private val trackDao: com.stash.core.data.db.dao.TrackDao = mockk(relaxed = true) {
        io.mockk.coEvery { findByAlias(any(), any()) } returns null
    }

    private val previewPlayer: PreviewPlayer = mockk(relaxed = true) {
        every { previewState } returns MutableStateFlow(PreviewState.Idle)
        every { playerErrors } returns MutableSharedFlow()
    }

    private fun delegate() = TrackActionsDelegate(
        previewPlayer = previewPlayer,
        searchPreviewMediaSource = mockk(relaxed = true),
        previewUrlExtractor = mockk(relaxed = true),
        previewUrlCache = mockk(relaxed = true),
        trackDao = trackDao,
        searchDownloadCoordinator = mockk(relaxed = true),
        playerRepository = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
    )

    private val item = TrackItem(
        videoId = "vid", title = "Song", artist = "Artist",
        durationSeconds = 200.0, thumbnailUrl = "http://art",
    )

    @Test
    fun `addToPlaylist links existing track without inserting`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { trackDao.findByYoutubeId("vid") } returns TrackEntity(id = 42L, title = "Song", artist = "Artist")

        d.addToPlaylist(item, playlistId = 7L)
        runCurrent()

        coVerify(exactly = 0) { trackDao.insert(any()) }
        coVerify(exactly = 1) { musicRepository.addTrackToPlaylist(42L, 7L) }
    }

    @Test
    fun `addToPlaylist inserts stub when absent then links`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { trackDao.findByYoutubeId("vid") } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { trackDao.insert(any()) } returns 99L

        d.addToPlaylist(item, playlistId = 7L)
        runCurrent()

        coVerify(exactly = 1) { trackDao.insert(any()) }
        coVerify(exactly = 1) { musicRepository.addTrackToPlaylist(99L, 7L) }
    }

    @Test
    fun `addToPlaylist of a live version does not add the library's studio row`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { trackDao.findByYoutubeId("live") } returns null
        // "Song (Live)" and "Song" share the loose words song|artist.
        coEvery { trackDao.findAllByCanonicalIdentity("song", "artist") } returns
            listOf(TrackEntity(id = 42L, title = "Song", artist = "Artist", filePath = "/m/song.opus", isDownloaded = true))
        coEvery { trackDao.insert(any()) } returns 99L

        d.addToPlaylist(item.copy(videoId = "live", title = "Song (Live)"), playlistId = 7L)
        runCurrent()

        coVerify(exactly = 1) { musicRepository.addTrackToPlaylist(99L, 7L) }
        coVerify(exactly = 0) { musicRepository.addTrackToPlaylist(42L, any()) }
    }

    @Test
    fun `addToPlaylist of the same song by its words links the library's copy`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { trackDao.findByYoutubeId("vid") } returns null
        // A lossless download of the song: a file, no video id, so nothing conflicts.
        coEvery { trackDao.findAllByCanonicalIdentity("song", "artist") } returns
            listOf(TrackEntity(id = 42L, title = "Song (feat. X)", artist = "Artist", filePath = "/m/song.flac", isDownloaded = true))

        d.addToPlaylist(item, playlistId = 7L)
        runCurrent()

        coVerify(exactly = 0) { trackDao.insert(any()) }
        coVerify(exactly = 1) { musicRepository.addTrackToPlaylist(42L, 7L) }
    }

    @Test
    fun `addToPlaylist refuses a blocklisted track`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns true

        d.addToPlaylist(item, playlistId = 7L)
        runCurrent()

        coVerify(exactly = 0) { trackDao.insert(any()) }
        coVerify(exactly = 0) { musicRepository.addTrackToPlaylist(any(), any()) }
    }
}
