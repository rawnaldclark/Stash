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
    fun `playTrack Offline-mode + stream-only mix track is not enqueued and prompts to switch online`() = runTest {
        // The player's offline master-gate silently skips stream-only tracks,
        // so Offline mode must NOT enqueue them. Instead the VM prompts the
        // user to switch to Online mode. (Connection state is irrelevant — the
        // Online/Offline toggle decides, not connectivity.)
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

        verifyBlocking(playerRepo, never()) { setQueue(any(), any(), any()) }
        assertThat(messages).contains("Switch to Online mode to play this track")

        msgJob.cancel()
        uiJob.cancel()
    }

    @Test
    fun `playAll Offline-mode + mix enqueues only downloaded tracks`() = runTest {
        // Offline mode: the queue is downloaded-only regardless of connection.
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
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L)

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
        )
    }
}
