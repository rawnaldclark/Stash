package com.stash.feature.home

import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.tipjar.TipJarRepository
import com.stash.core.data.tipjar.TipJarState
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.data.download.backfill.MetadataBackfillState
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.verifyBlocking

/**
 * Discovery-reshape contract for [HomeViewModel]: the Discover hero derives
 * from the builtin Daily Discover playlist, and [HomeUiState.isColdStart]
 * tracks its presence. [playHero] honours the Online/Offline toggle (offline
 * enqueues downloaded-only; streaming enqueues everything) — the gate that
 * used to live on the now-relocated `playPlaylist`.
 *
 * Same harness as :feature:library's LibraryViewModelMixTest —
 * mockito-kotlin, StandardTestDispatcher, mock collaborators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val downloaded = Track(
        id = 1L, title = "Local", artist = "A",
        isStreamable = true, isDownloaded = true,
        filePath = "/m/1.opus",
    )
    private val streamOnly = Track(
        id = 42L, title = "Cloud", artist = "A",
        isStreamable = true, isDownloaded = false, filePath = null,
    )

    // ------------------------------------------------------------------
    // Hero / cold-start
    // ------------------------------------------------------------------

    @Test
    fun `hero materializes when builtin Daily Discover has tracks`() = runTest {
        val daily = dailyDiscover(id = 7L, trackCount = 30, artUrl = "https://art/7")
        val vm = buildVm(playlists = listOf(daily), builtinIds = listOf(7L))

        val state = vm.uiState.first { !it.isLoading }

        val hero = state.hero
        assertThat(hero).isNotNull()
        assertThat(hero!!.title).isEqualTo("Daily Discover")
        assertThat(hero.subtitle).isEqualTo("30 tracks · updated daily")
        assertThat(hero.artUrl).isEqualTo("https://art/7")
        assertThat(hero.playlistId).isEqualTo(7L)
        assertThat(state.isColdStart).isFalse()
    }

    @Test
    fun `no hero when builtin Daily Discover is empty`() = runTest {
        val daily = dailyDiscover(id = 7L, trackCount = 0)
        val vm = buildVm(playlists = listOf(daily), builtinIds = listOf(7L))

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.hero).isNull()
        assertThat(state.isColdStart).isTrue()
    }

    @Test
    fun `no hero when there is no builtin playlist`() = runTest {
        val vm = buildVm(playlists = emptyList(), builtinIds = emptyList())

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.hero).isNull()
        assertThat(state.isColdStart).isTrue()
    }

    // ------------------------------------------------------------------
    // playHero — Online/Offline gate
    // ------------------------------------------------------------------

    @Test
    fun `playHero offline enqueues only downloaded`() = runTest {
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlists = listOf(dailyDiscover(id = 7L, trackCount = 2)),
            builtinIds = listOf(7L),
            heroTracks = listOf(downloaded, streamOnly),
            streamingEnabled = false,
            playerRepository = playerRepo,
        )
        vm.uiState.first { !it.isLoading } // materialize the hero

        vm.playHero()
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `playHero streaming on enqueues downloaded and stream-only`() = runTest {
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlists = listOf(dailyDiscover(id = 7L, trackCount = 2)),
            builtinIds = listOf(7L),
            heroTracks = listOf(downloaded, streamOnly),
            streamingEnabled = true,
            playerRepository = playerRepo,
        )
        vm.uiState.first { !it.isLoading } // materialize the hero

        vm.playHero()
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L, 42L)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun dailyDiscover(id: Long, trackCount: Int, artUrl: String? = null) = Playlist(
        id = id,
        name = "Daily Discover",
        source = MusicSource.SPOTIFY,
        type = PlaylistType.STASH_MIX,
        trackCount = trackCount,
        artUrl = artUrl,
    )

    private fun buildVm(
        playlists: List<Playlist> = emptyList(),
        builtinIds: List<Long> = emptyList(),
        heroTracks: List<Track> = emptyList(),
        streamingEnabled: Boolean = true,
        playerRepository: PlayerRepository = mock(),
    ): HomeViewModel {
        val musicRepo = mock<MusicRepository> {
            on { getAllPlaylists() } doReturn flowOf(playlists)
            on { getTracksByPlaylist(any()) } doReturn flowOf(heroTracks)
        }
        val recipeDao = mock<StashMixRecipeDao> {
            onBlocking { getBuiltinPlaylistIds() } doReturn builtinIds
        }
        val streamingPreference = mock<StreamingPreference> {
            on { enabled } doReturn flowOf(streamingEnabled)
            onBlocking { current() } doReturn streamingEnabled
        }
        val losslessPrefs = mock<LosslessSourcePreferences> {
            on { enabled } doReturn flowOf(false)
            on { bannerDismissed } doReturn flowOf(false)
        }
        // init {} reads isStale() (suspend, primitive Boolean) — stub it so the
        // cold-start warm-up coroutine doesn't NPE on an unboxed null. `state`
        // feeds the uiState combine, so it must emit.
        val tipJar = mock<TipJarRepository> {
            on { state } doReturn MutableStateFlow(TipJarState.EMPTY)
            onBlocking { isStale() } doReturn false
        }
        val metadataBackfill = mock<MetadataBackfillState> {
            on { snapshot } doReturn flowOf(
                MetadataBackfillState.BackfillSnapshot(
                    MetadataBackfillState.State.IDLE, 0, 0, 0, null,
                ),
            )
        }
        return HomeViewModel(
            musicRepository = musicRepo,
            playerRepository = playerRepository,
            losslessPrefs = losslessPrefs,
            settingsDeepLinkController = mock(),
            tipJarRepository = tipJar,
            recipeDao = recipeDao,
            streamingPreference = streamingPreference,
            metadataBackfillState = metadataBackfill,
            context = mock(),
        )
    }
}
