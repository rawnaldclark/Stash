package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking

/**
 * Task 5 of the Stash Mixes Stream-Only plan: tap-time offline guard.
 *
 * Stream-only Mix tracks (isStreamable=true, isDownloaded=false) must NOT
 * enqueue when the device has no validated internet. Instead the VM emits a
 * "Online only — connect to play this track" message on [userMessages] so
 * the screen shows a Snackbar. Downloaded tracks play normally even offline;
 * stream-only tracks play normally when online.
 *
 * Mirrors the harness in [LikedSongsDetailViewModelTest] — mockito-kotlin,
 * StandardTestDispatcher, mock collaborators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MixOfflineTapGuardTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `playTrack offline + stream-only emits Snackbar and skips setQueue`() = runTest {
        val streamOnly = Track(
            id = 42L,
            title = "Cloud Track",
            artist = "A",
            isStreamable = true,
            isDownloaded = false,
            filePath = null,
        )
        val playlist = playlist(id = 7L)
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        }
        val connectivity = mock<ConnectivityMonitor> {
            on { isConnected() } doReturn false
        }
        val vm = buildVm(
            playlistId = playlist.id,
            tracks = listOf(streamOnly),
            playlist = playlist,
            playerRepository = playerRepo,
            connectivityMonitor = connectivity,
        )

        val messages = mutableListOf<String>()
        val msgJob = backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        val uiJob = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playTrack(trackId = 42L)
        runCurrent()

        verifyBlocking(playerRepo, never()) { setQueue(any(), any(), any()) }
        assertThat(messages).contains("Online only — connect to play this track")

        msgJob.cancel()
        uiJob.cancel()
    }

    @Test
    fun `playTrack offline + downloaded does NOT trigger guard and plays`() = runTest {
        val downloaded = Track(
            id = 99L,
            title = "Local Track",
            artist = "A",
            isStreamable = true,
            isDownloaded = true,
            filePath = "/storage/emulated/0/Music/local.opus",
        )
        val playlist = playlist(id = 7L)
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        }
        val connectivity = mock<ConnectivityMonitor> {
            on { isConnected() } doReturn false
        }
        val vm = buildVm(
            playlistId = playlist.id,
            tracks = listOf(downloaded),
            playlist = playlist,
            playerRepository = playerRepo,
            connectivityMonitor = connectivity,
        )

        val messages = mutableListOf<String>()
        val msgJob = backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        val uiJob = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playTrack(trackId = 99L)
        runCurrent()

        verifyBlocking(playerRepo) { setQueue(any(), any(), any()) }
        assertThat(messages).doesNotContain("Online only — connect to play this track")

        msgJob.cancel()
        uiJob.cancel()
    }

    @Test
    fun `playTrack online + stream-only does NOT trigger guard and plays`() = runTest {
        val streamOnly = Track(
            id = 42L,
            title = "Cloud Track",
            artist = "A",
            isStreamable = true,
            isDownloaded = false,
            filePath = null,
        )
        val playlist = playlist(id = 7L)
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        }
        val connectivity = mock<ConnectivityMonitor> {
            on { isConnected() } doReturn true
        }
        val vm = buildVm(
            playlistId = playlist.id,
            tracks = listOf(streamOnly),
            playlist = playlist,
            playerRepository = playerRepo,
            connectivityMonitor = connectivity,
        )

        val messages = mutableListOf<String>()
        val msgJob = backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        val uiJob = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playTrack(trackId = 42L)
        runCurrent()

        verifyBlocking(playerRepo) { setQueue(any(), any(), any()) }
        assertThat(messages).doesNotContain("Online only — connect to play this track")

        msgJob.cancel()
        uiJob.cancel()
    }

    @Test
    fun `playTrack in Download mode + stream-only mix track is enqueued`() = runTest {
        // The Download switch decides what sync writes to disk, never what
        // plays: with a connection, a stream-only track is queued whichever
        // way it is set. (Until 2026-09-17 Offline mode refused it with a
        // "Switch to Online mode" prompt.)
        val downloaded = Track(
            id = 1L, title = "Local", artist = "A",
            isStreamable = true, isDownloaded = true,
            filePath = "/storage/emulated/0/Music/local.opus",
        )
        val streamOnly = Track(
            id = 42L, title = "Cloud", artist = "A",
            isStreamable = true, isDownloaded = false, filePath = null,
        )
        val playlist = playlist(id = 7L)
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        }
        val connectivity = mock<ConnectivityMonitor> { on { isConnected() } doReturn true }
        val offlinePref = mock<StreamingPreference> { onBlocking { current() } doReturn false }
        val vm = buildVm(
            playlistId = playlist.id,
            tracks = listOf(downloaded, streamOnly),
            playlist = playlist,
            playerRepository = playerRepo,
            connectivityMonitor = connectivity,
            streamingPreference = offlinePref,
        )

        val messages = mutableListOf<String>()
        val msgJob = backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        val uiJob = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playTrack(trackId = 42L)
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L, 42L)
        assertThat(messages).isEmpty()

        msgJob.cancel()
        uiJob.cancel()
    }

    @Test
    fun `playAll in Download mode enqueues downloaded and stream-only alike`() = runTest {
        // The queue is the whole playlist in either mode.
        val downloaded = Track(
            id = 1L, title = "Local", artist = "A",
            isStreamable = true, isDownloaded = true,
            filePath = "/storage/emulated/0/Music/local.opus",
        )
        val streamOnly = Track(
            id = 42L, title = "Cloud", artist = "A",
            isStreamable = true, isDownloaded = false, filePath = null,
        )
        val playlist = playlist(id = 7L)
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        }
        val connectivity = mock<ConnectivityMonitor> { on { isConnected() } doReturn true }
        val offlinePref = mock<StreamingPreference> { onBlocking { current() } doReturn false }
        val vm = buildVm(
            playlistId = playlist.id,
            tracks = listOf(downloaded, streamOnly),
            playlist = playlist,
            playerRepository = playerRepo,
            connectivityMonitor = connectivity,
            streamingPreference = offlinePref,
        )

        val uiJob = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playAll()
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L, 42L)

        uiJob.cancel()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun playlist(id: Long) = Playlist(
        id = id,
        name = "Mix",
        source = MusicSource.SPOTIFY,
        type = PlaylistType.STASH_MIX,
    )

    private fun buildVm(
        playlistId: Long,
        tracks: List<Track>,
        playlist: Playlist,
        playerRepository: PlayerRepository,
        connectivityMonitor: ConnectivityMonitor,
        streamingPreference: StreamingPreference = mock {
            onBlocking { current() } doReturn true
        },
    ): PlaylistDetailViewModel {
        val musicRepo = mock<MusicRepository> {
            on { getTracksByPlaylist(playlistId) } doReturn flowOf(tracks)
            on { observePlaylist(any()) } doReturn flowOf(null)
            onBlocking { getPlaylistWithTracks(playlistId) } doReturn playlist
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        }
        val savedStateHandle = SavedStateHandle(mapOf("playlistId" to playlistId))
        return PlaylistDetailViewModel(
            savedStateHandle = savedStateHandle,
            musicRepository = musicRepo,
            playerRepository = playerRepository,
            playlistImageHelper = mock(),
            streamingPreference = streamingPreference,
            connectivityMonitor = connectivityMonitor,
            // buildState flow reads these at construction — stub to empty flows.
            recipeDao = mock {
                on { observeAll() } doReturn flowOf(emptyList())
            },
            discoveryQueueDao = mock {
                on { observeNonFailedCountsByRecipe() } doReturn flowOf(emptyList())
            },
            sharedMixRepository = mock {
                on { observe(any()) } doReturn flowOf(null)
            },
            downloadQueueDao = mock {
                on { observeGivenUpTrackIds(any()) } doReturn flowOf(emptyList())
            },
        )
    }
}
