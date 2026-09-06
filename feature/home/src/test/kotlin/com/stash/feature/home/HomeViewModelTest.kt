package com.stash.feature.home

import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.discovery.HomeDiscoveryRepository
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
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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

    // ------------------------------------------------------------------
    // "Your playlists" Liked card
    // ------------------------------------------------------------------

    @Test
    fun `liked card de-dupes the merged count across liked playlists`() = runTest {
        // getTracksByPlaylist(any()) returns the same list for BOTH liked
        // playlists: a naive sum would be 6, the de-duped union is 3.
        val liked = listOf(
            Track(id = 1L, title = "A", artist = "X"),
            Track(id = 2L, title = "B", artist = "X"),
            Track(id = 3L, title = "C", artist = "X"),
        )
        val vm = buildVm(
            heroTracks = liked,
            likedCardEnabled = true,
            likedPlaylists = listOf(
                Playlist(id = 91L, name = "Stash Liked", source = MusicSource.LOCAL, type = PlaylistType.STASH_LIKED),
                Playlist(id = 92L, name = "Liked Songs", source = MusicSource.SPOTIFY, type = PlaylistType.LIKED_SONGS),
            ),
        )

        val state = vm.uiState.first { it.likedCard != null }

        assertThat(state.likedCard?.trackCount).isEqualTo(3)
    }

    @Test
    fun `liked card stays null while the setting is off`() = runTest {
        val vm = buildVm(likedCardEnabled = false)

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.likedCard).isNull()
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
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any(), any()) }
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
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L, 42L)
    }

    // ------------------------------------------------------------------
    // Lossless-offline banner
    // ------------------------------------------------------------------

    /** qbdlx has missed enough consecutive resolves to read as down. */
    private fun downHealth() = com.stash.core.media.streaming.LosslessSourceHealth().apply {
        repeat(com.stash.core.media.streaming.LosslessSourceHealth.QBDLX_DOWN_THRESHOLD) {
            recordQbdlxMiss()
        }
    }

    @Test
    fun `lossless offline shows when qbdlx looks dead and nothing is user-owned`() = runTest {
        val vm = buildVm(
            losslessEnabled = true,
            losslessSourceHealth = downHealth(),
            anyUserOwned = false,
        )

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.showLosslessOffline).isTrue()
    }

    /**
     * The regression this rewrite fixes: the old ARCOD-only check told a user
     * with their own Qobuz account connected to go connect a lossless source
     * they already have.
     */
    @Test
    fun `lossless offline hidden for a user who owns a lossless source`() = runTest {
        val vm = buildVm(
            losslessEnabled = true,
            losslessSourceHealth = downHealth(),
            anyUserOwned = true,
        )

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.showLosslessOffline).isFalse()
    }

    @Test
    fun `lossless offline hidden while qbdlx is healthy`() = runTest {
        val vm = buildVm(losslessEnabled = true, anyUserOwned = false)

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.showLosslessOffline).isFalse()
    }

    @Test
    fun `lossless offline hidden while lossless is switched off`() = runTest {
        val vm = buildVm(
            losslessEnabled = false,
            losslessSourceHealth = downHealth(),
            anyUserOwned = false,
        )

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.showLosslessOffline).isFalse()
    }

    @Test
    fun `lossless offline hidden once dismissed`() = runTest {
        val vm = buildVm(
            losslessEnabled = true,
            losslessSourceHealth = downHealth(),
            anyUserOwned = false,
            losslessOfflineDismissed = true,
        )

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.showLosslessOffline).isFalse()
    }

    @Test
    fun `dismissLosslessOffline writes its own key, not the old ARCOD one`() = runTest {
        val prefs = losslessPrefsMock(losslessEnabled = true)
        val vm = buildVm(losslessPrefs = prefs)

        vm.dismissLosslessOffline()
        runCurrent()

        verifyBlocking(prefs) { setLosslessOfflineDismissed(true) }
        verifyBlocking(prefs, never()) { setArcodRescueDismissed(any()) }
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

    private fun albumSummary(id: String) = AlbumSummary(
        id = id, title = "T", artist = "A", thumbnailUrl = null, year = null,
        source = AlbumSource.QOBUZ,
    )

    private fun buildVm(
        playlists: List<Playlist> = emptyList(),
        builtinIds: List<Long> = emptyList(),
        heroTracks: List<Track> = emptyList(),
        // Named to avoid shadowing HomeSectionsPreference.showLikedOnHome
        // inside the `on { }` stubbing lambda (params outrank the receiver).
        likedCardEnabled: Boolean = false,
        likedPlaylists: List<Playlist> = emptyList(),
        streamingEnabled: Boolean = true,
        playerRepository: PlayerRepository = mock(),
        discoveryAlbums: List<AlbumSummary> = emptyList(),
        homeDiscovery: HomeDiscoveryRepository? = null,
        losslessEnabled: Boolean = false,
        losslessSourceHealth: com.stash.core.media.streaming.LosslessSourceHealth =
            com.stash.core.media.streaming.LosslessSourceHealth(),
        anyUserOwned: Boolean = false,
        losslessOfflineDismissed: Boolean = false,
        losslessPrefs: LosslessSourcePreferences? = null,
    ): HomeViewModel {
        val musicRepo = mock<MusicRepository> {
            on { getAllPlaylists() } doReturn flowOf(playlists)
            on { getTracksByPlaylist(any()) } doReturn flowOf(heroTracks)
            // Feeds likedCardFlow when showLikedOnHome is on — an unstubbed
            // (null) flow would NPE that combine (same trap as below).
            on { getPlaylistsByType(eq(PlaylistType.STASH_LIKED)) } doReturn
                flowOf(likedPlaylists.filter { it.type == PlaylistType.STASH_LIKED })
            on { getPlaylistsByType(eq(PlaylistType.LIKED_SONGS)) } doReturn
                flowOf(likedPlaylists.filter { it.type == PlaylistType.LIKED_SONGS })
        }
        val recipeDao = mock<StashMixRecipeDao> {
            onBlocking { getBuiltinPlaylistIds() } doReturn builtinIds
            // Feeds the homePlaylistFlow combine — an unstubbed (null) flow
            // NPEs the combine and uiState never emits (tests hang).
            on { observeAll() } doReturn flowOf(emptyList())
        }
        val streamingPreference = mock<StreamingPreference> {
            on { enabled } doReturn flowOf(streamingEnabled)
            onBlocking { current() } doReturn streamingEnabled
        }
        val prefs = losslessPrefs ?: losslessPrefsMock(losslessEnabled, losslessOfflineDismissed)
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
        val discovery = homeDiscovery ?: mock<HomeDiscoveryRepository> {
            onBlocking { newReleases(anyOrNull()) } doReturn discoveryAlbums
            onBlocking { topAlbums(anyOrNull()) } doReturn emptyList()
            onBlocking { communityPlaylists(anyOrNull()) } doReturn emptyList()
        }
        // Feeds the homePlaylistFlow combine — must emit or uiState never does.
        val discoveryQueueDao = mock<com.stash.core.data.db.dao.DiscoveryQueueDao> {
            on { observeNonFailedCountsByRecipe() } doReturn flowOf(emptyList())
        }
        // Same deal: the rail-recency source is the 5th arm of that combine, and
        // an unstubbed Flow returns null, so combine never emits and every test
        // times out rather than failing on an assertion.
        val playlistDao = mock<com.stash.core.data.db.dao.PlaylistDao> {
            on { observeLatestAdditionPerPlaylist() } doReturn flowOf(emptyList())
        }
        return HomeViewModel(
            musicRepository = musicRepo,
            playerRepository = playerRepository,
            losslessPrefs = prefs,
            settingsDeepLinkController = mock(),
            libraryDeepLinkController = com.stash.core.data.navigation.LibraryDeepLinkController(),
            tipJarRepository = tipJar,
            recipeDao = recipeDao,
            discoveryQueueDao = discoveryQueueDao,
            playlistDao = playlistDao,
            downloadNetworkPreference = mock(),
            streamingPreference = streamingPreference,
            metadataBackfillState = metadataBackfill,
            homeDiscoveryRepository = discovery,
            // Discovery on + every section visible = rows fetch, matching the
            // pre-existing test expectations.
            homeDiscoveryPreference = mock {
                on { enabled } doReturn flowOf(true)
            },
            homeSectionsPreference = mock {
                on { visibleSections } doReturn flowOf(com.stash.core.data.prefs.HomeSection.entries.toList())
                // Feeds likedCardFlow — an unstubbed (null) flow NPEs the
                // uiState pairing combine and every test hangs, not fails.
                on { showLikedOnHome } doReturn flowOf(likedCardEnabled)
            },
            // Defaults: fresh health (starts healthy) + nothing user-owned, so
            // the lossless-offline banner stays out of existing tests.
            losslessSourceHealth = losslessSourceHealth,
            losslessAvailability = mock {
                on { this.anyUserOwned } doReturn flowOf(anyUserOwned)
            },
            context = mock(),
        )
    }

    /**
     * Every lossless pref the ViewModel's combines read. An unstubbed Flow is
     * null, which NPEs the combine and hangs the test instead of failing it.
     */
    private fun losslessPrefsMock(
        losslessEnabled: Boolean = false,
        losslessOfflineDismissed: Boolean = false,
    ) = mock<LosslessSourcePreferences> {
        on { enabled } doReturn flowOf(losslessEnabled)
        on { bannerDismissed } doReturn flowOf(false)
        on { this.losslessOfflineDismissed } doReturn flowOf(losslessOfflineDismissed)
    }

    // ------------------------------------------------------------------
    // Qobuz discovery rows + genre filter
    // ------------------------------------------------------------------

    @Test
    fun `discovery rows load for All genre on init`() = runTest {
        val vm = buildVm(discoveryAlbums = listOf(albumSummary("a1")))

        val state = vm.uiState.first { it.newReleases.isNotEmpty() }

        assertThat(state.selectedGenre).isEqualTo("All")
        assertThat(state.newReleases.single().id).isEqualTo("a1")
    }

    @Test
    fun `onSelectGenre re-fetches rows with that genre id`() = runTest {
        val repo = mock<HomeDiscoveryRepository> {
            onBlocking { newReleases(anyOrNull()) } doReturn emptyList()
            onBlocking { topAlbums(anyOrNull()) } doReturn emptyList()
            onBlocking { communityPlaylists(anyOrNull()) } doReturn emptyList()
        }
        val vm = buildVm(homeDiscovery = repo)
        vm.uiState.first { !it.isLoading }

        vm.onSelectGenre("Pop/Rock")
        runCurrent()

        assertThat(vm.uiState.value.selectedGenre).isEqualTo("Pop/Rock")
        verifyBlocking(repo) { newReleases(eq(112)) }   // Pop/Rock genre_id
    }
}
