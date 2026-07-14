package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.Track
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Covers the Qobuz branch of [AlbumDiscoveryViewModel] (Task 12): source
 * routing to the cache, persist-for-resume queue building, guarding the
 * videoId-keyed side-effects, and the YT regression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDiscoveryViewModelQobuzTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun qobuzTrack(title: String) = TrackSummary(
        videoId = "", title = title, artist = "MBV", album = "Loveless",
        durationSeconds = 200.0, thumbnailUrl = null,
    )

    private fun qobuzDetail() = AlbumDetail(
        id = "123", title = "Loveless", artist = "MBV", artistId = null,
        thumbnailUrl = null, year = "1991",
        tracks = listOf(qobuzTrack("Only Shallow"), qobuzTrack("Loomer")),
        moreByArtist = emptyList(),
    )

    private fun ytDetail() = AlbumDetail(
        id = "MPRE1", title = "YT Album", artist = "A", artistId = null,
        thumbnailUrl = null, year = "2005",
        tracks = listOf(
            TrackSummary("vid1", "T1", "A", "YT Album", 180.0, null),
        ),
        moreByArtist = emptyList(),
    )

    private fun vm(
        source: AlbumSource,
        cache: AlbumCache,
        prefetcher: PreviewPrefetcher = mock(),
        player: PlayerRepository = mock(),
        musicRepo: MusicRepository = mock(),
        delegate: TrackActionsDelegate = stubDelegate(),
    ) = AlbumDiscoveryViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "browseId" to (if (source == AlbumSource.QOBUZ) "123" else "MPRE1"),
                "title" to "T",
                "artist" to "A",
                "thumbnailUrl" to null,
                "year" to null,
                "source" to source,
            ),
        ),
        albumCache = cache,
        prefetcher = prefetcher,
        playerRepository = player,
        musicRepository = musicRepo,
        streamingPreference = mock(),
        delegate = delegate,
        losslessPrefetcher = mock(),
    )

    private fun stubDelegate(): TrackActionsDelegate = mock {
        on { previewState } doReturn
            MutableStateFlow(PreviewState.Idle as PreviewState).asStateFlow()
        on { userMessages } doReturn MutableSharedFlow<String>().asSharedFlow()
        on { downloadingIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { downloadedIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { previewLoadingId } doReturn MutableStateFlow<String?>(null).asStateFlow()
        on { userPlaylists } doReturn MutableStateFlow(emptyList<com.stash.core.model.Playlist>())
    }

    @Test fun `qobuz album passes its source to AlbumCache`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(eq("123"), eq(AlbumSource.QOBUZ))).thenReturn(qobuzDetail())
        vm(AlbumSource.QOBUZ, cache); advanceUntilIdle()
        verify(cache).get(eq("123"), eq(AlbumSource.QOBUZ))
    }

    @Test fun `qobuz play persists tracks and queues real ids with null youtubeId`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(qobuzDetail())
        val musicRepo = mock<MusicRepository>()
        whenever(musicRepo.ensureTrackPersisted(any())).thenReturn(1001L, 1002L)
        val player = mock<PlayerRepository>()
        val vm = vm(AlbumSource.QOBUZ, cache, musicRepo = musicRepo, player = player)
        advanceUntilIdle()

        vm.playAlbum(0); advanceUntilIdle()

        val cap = argumentCaptor<List<Track>>()
        verify(player).setQueue(cap.capture(), eq(0))
        assertEquals(listOf(1001L, 1002L), cap.firstValue.map { it.id })
        assertTrue(cap.firstValue.all { it.youtubeId == null })
    }

    @Test fun `qobuz PLAYLIST play persists tracks with distinct real ids (regression)`() = runTest {
        // Regression: a QOBUZ_PLAYLIST source used to fall through to the YouTube
        // path, giving every track id = "".hashCode() = 0 and youtubeId = "" —
        // so the queue collapsed to one row and playback refused to advance past
        // the first track. It must take the native path (persist → distinct ids).
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(qobuzDetail())
        val musicRepo = mock<MusicRepository>()
        whenever(musicRepo.ensureTrackPersisted(any())).thenReturn(2001L, 2002L)
        val player = mock<PlayerRepository>()
        val vm = vm(AlbumSource.QOBUZ_PLAYLIST, cache, musicRepo = musicRepo, player = player)
        advanceUntilIdle()

        vm.playAlbum(0); advanceUntilIdle()

        val cap = argumentCaptor<List<Track>>()
        verify(player).setQueue(cap.capture(), eq(0))
        assertEquals(listOf(2001L, 2002L), cap.firstValue.map { it.id })
        assertTrue(cap.firstValue.all { it.youtubeId == null })
    }

    @Test fun `qobuz album does NOT run videoId-keyed side effects`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(qobuzDetail())
        val prefetcher = mock<PreviewPrefetcher>()
        val musicRepo = mock<MusicRepository>()
        val delegate = stubDelegate()
        vm(AlbumSource.QOBUZ, cache, prefetcher = prefetcher, musicRepo = musicRepo, delegate = delegate)
        advanceUntilIdle()

        verify(prefetcher, never()).prefetch(any())
        verify(delegate, never()).refreshDownloadedIds(any())
        verify(musicRepo, never()).backfillAlbumForTracks(any(), any(), any())
    }

    @Test fun `youtube album still runs prefetch (regression)`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(ytDetail())
        val prefetcher = mock<PreviewPrefetcher>()
        vm(AlbumSource.YOUTUBE, cache, prefetcher = prefetcher); advanceUntilIdle()
        verify(prefetcher).prefetch(any())
    }
}
