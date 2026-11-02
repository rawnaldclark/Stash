package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.SCANNED_UNKNOWN_ARTIST
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * The album page opened on a YouTube Music playlist ([AlbumSource.YOUTUBE_PLAYLIST], from Home's Moods & moments):
 * each song keeps its own artist and album in its row's actions and in the queue, the playlist's name is never
 * written into its songs' album, it's saved under the key a pasted link of it uses, and it's worded as a playlist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDiscoveryPlaylistTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private val yourOtherLife = TrackSummary("H-zVEnHB3_g", "Your Other Life", "Lord Huron", "Long Lost", 240.0, "art-1")
    private val nightCall = TrackSummary("HedhexHvLZs", "Night Call", "Kavinsky", "OutRun", 258.0, "art-2")
    private val noArtist = TrackSummary("LKroEZ9vVyw", "Rain on a tin roof", "", null, 600.0, null)

    private fun playlist(tracks: List<TrackSummary> = listOf(yourOtherLife, nightCall)) = AlbumDetail(
        id = PLAYLIST_ID, title = "Coffee Shop Blend", artist = "YouTube Music", artistId = null,
        thumbnailUrl = "cover", year = null, tracks = tracks, moreByArtist = emptyList(),
    )

    private fun cacheOf(detail: AlbumDetail, source: AlbumSource = AlbumSource.YOUTUBE_PLAYLIST): AlbumCache = mock {
        onBlocking { get(eq(PLAYLIST_ID), eq(source)) } doReturn detail
    }

    private fun vm(
        cache: AlbumCache,
        source: AlbumSource = AlbumSource.YOUTUBE_PLAYLIST,
        player: PlayerRepository = mock(),
        musicRepo: MusicRepository = mock(),
        userPlaylists: List<Playlist> = emptyList(),
    ) = AlbumDiscoveryViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "browseId" to PLAYLIST_ID,
                "title" to "Coffee Shop Blend",
                "artist" to "YouTube Music",
                "thumbnailUrl" to "cover",
                "year" to null,
                "source" to source,
            ),
        ),
        albumCache = cache,
        ytMusicApiClient = mock(),
        prefetcher = mock(),
        playerRepository = player,
        musicRepository = musicRepo,
        streamingPreference = mock(),
        delegate = stubDelegate(userPlaylists),
        losslessPrefetcher = mock(),
    )

    private fun stubDelegate(userPlaylists: List<Playlist>): TrackActionsDelegate = mock {
        on { previewState } doReturn MutableStateFlow(PreviewState.Idle as PreviewState).asStateFlow()
        on { userMessages } doReturn MutableSharedFlow<String>().asSharedFlow()
        on { downloadingIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { downloadedIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { previewLoadingId } doReturn MutableStateFlow<String?>(null).asStateFlow()
        on { this.userPlaylists } doReturn MutableStateFlow(userPlaylists)
    }

    @Test
    fun `a playlist never writes its name into its songs' album, where an album page does`() = runTest {
        val musicRepo = mock<MusicRepository>()
        vm(cacheOf(playlist()), musicRepo = musicRepo)
        advanceUntilIdle()
        verify(musicRepo, never()).backfillAlbumForTracks(any(), any(), any())

        // The same page as an album fills it in: the check above is the playlist's, not a backfill that never runs
        val albumRepo = mock<MusicRepository>()
        vm(cacheOf(playlist(), AlbumSource.YOUTUBE), source = AlbumSource.YOUTUBE, musicRepo = albumRepo)
        advanceUntilIdle()
        verify(albumRepo).backfillAlbumForTracks(eq(listOf("H-zVEnHB3_g", "HedhexHvLZs")), eq("Coffee Shop Blend"), eq("YouTube Music"))
    }

    @Test
    fun `a row's actions carry the song's own artist and album, never the playlist's`() = runTest {
        val vm = vm(cacheOf(playlist()))
        advanceUntilIdle()

        val item = vm.trackItemFor(nightCall)
        assertEquals("Kavinsky", item.artist)
        assertEquals("OutRun", item.album)
        assertNull(item.albumArtist)
        assertEquals("HedhexHvLZs", item.videoId)
    }

    @Test
    fun `the queue carries each song's own artist and album, from the row tapped`() = runTest {
        val player = mock<PlayerRepository>()
        val vm = vm(cacheOf(playlist()), player = player)
        advanceUntilIdle()

        vm.playAlbum(startIndex = 1)
        advanceUntilIdle()

        val queued = argumentCaptor<List<Track>>()
        verify(player).setQueue(queued.capture(), eq(1), any())
        assertEquals(listOf("Lord Huron", "Kavinsky"), queued.firstValue.map { it.artist })
        assertEquals(listOf("Long Lost", "OutRun"), queued.firstValue.map { it.album })
        assertEquals(listOf("H-zVEnHB3_g", "HedhexHvLZs"), queued.firstValue.map { it.youtubeId })
        assertEquals(listOf("art-1", "art-2"), queued.firstValue.map { it.albumArtUrl })
    }

    @Test
    fun `a song YouTube names no artist for is Unknown Artist, never blank or the playlist's maker`() = runTest {
        val player = mock<PlayerRepository>()
        val vm = vm(cacheOf(playlist(listOf(yourOtherLife, noArtist))), player = player)
        advanceUntilIdle()

        assertEquals(SCANNED_UNKNOWN_ARTIST, vm.trackItemFor(noArtist).artist)
        vm.playAlbum()
        advanceUntilIdle()
        val queued = argumentCaptor<List<Track>>()
        verify(player).setQueue(queued.capture(), eq(0), any())
        assertEquals(listOf("Lord Huron", SCANNED_UNKNOWN_ARTIST), queued.firstValue.map { it.artist })
        // No album is no album: never the playlist's name
        assertEquals("", queued.firstValue.last().album)
    }

    @Test
    fun `a playlist that didn't load offers Retry, worded as a playlist`() = runTest {
        val cache = mock<AlbumCache> {
            onBlocking { get(any(), any()) } doSuspendableAnswer { throw IOException("YouTube Music didn't return playlist $PLAYLIST_ID") }
        }
        val vm = vm(cache)
        assertTrue(vm.isPlaylist)
        vm.userMessages.test {
            advanceUntilIdle()
            assertEquals("Couldn't load playlist — tap Retry.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            AlbumDiscoveryStatus.Error(AlbumDiscoveryViewModel.LOAD_FAILED, canRetry = true),
            vm.uiState.value.status,
        )
    }

    @Test
    fun `adding it to the queue is worded as a playlist`() = runTest {
        val player = mock<PlayerRepository>()
        whenever(player.addToQueue(any<List<Track>>())).thenReturn(true)
        val vm = vm(cacheOf(playlist()), player = player)
        advanceUntilIdle()
        vm.userMessages.test {
            vm.addAlbumToQueue()
            advanceUntilIdle()
            assertEquals("Added playlist to queue", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `it's saved under the pasted link's key, and a pasted copy already counts as saved`() = runTest {
        val musicRepo = mock<MusicRepository>()
        whenever(musicRepo.ensureTrackPersisted(any())).thenReturn(11L, 12L)
        whenever(musicRepo.ensureCustomPlaylist(any(), any(), anyOrNull())).thenReturn(77L)
        val vm = vm(cacheOf(playlist()), musicRepo = musicRepo)
        advanceUntilIdle()

        vm.saveAlbum()
        advanceUntilIdle()
        // music.youtube.com/playlist?list=RDCLAK5uy_1 pasted in Sync files it under the same key
        verify(musicRepo).ensureCustomPlaylist(eq("Coffee Shop Blend"), eq("custom_link_yt_RDCLAK5uy_1"), eq("cover"))
        verify(musicRepo).addTracksToPlaylist(listOf(11L, 12L), 77L)

        val pasted = Playlist(id = 5L, name = "Coffee Shop Blend", source = MusicSource.BOTH, sourceId = "custom_link_yt_RDCLAK5uy_1")
        val again = vm(cacheOf(playlist()), userPlaylists = listOf(pasted))
        advanceUntilIdle()
        assertTrue(again.isSaved.value)
    }

    private companion object {
        const val PLAYLIST_ID = "VLRDCLAK5uy_1"
    }
}
