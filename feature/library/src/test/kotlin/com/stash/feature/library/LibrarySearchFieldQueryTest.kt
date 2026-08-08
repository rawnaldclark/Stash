package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.PlaylistType
import com.stash.data.download.files.LocalImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

/**
 * Regression guard for the v0.9.83 Library-search bug: typing dropped characters
 * and the field fought you.
 *
 * Cause: the search `TextField` is fully controlled, and its value was read from
 * `uiState.searchQuery`. f56a6e51 put that whole flow behind
 * `flowOn(Dispatchers.Default)` together with the full library filter+sort, so
 * the typed character could no longer come back inside the frame — each
 * recomposition stamped the stale query over the field.
 *
 * The contract these tests pin: **the field-facing query updates synchronously,
 * with no dispatcher work at all.** Every assertion below runs WITHOUT advancing
 * the test scheduler — which is exactly what fails if anyone routes
 * [LibraryViewModel.searchQuery] back through the async pipeline, since nothing
 * behind `flowOn`/`stateIn` can have run yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchFieldQueryTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `field query reflects the keystroke immediately, without the pipeline running`() = runTest {
        val vm = buildVm()

        vm.setSearchQuery("r")

        // No advanceUntilIdle() on purpose: a value that needed the Default
        // pipeline would still be "" here.
        assertThat(vm.searchQuery.value).isEqualTo("r")
    }

    @Test fun `every keystroke of a fast burst lands in order`() = runTest {
        val vm = buildVm()

        // Simulates the IME driving a controlled field faster than the filter
        // pipeline can answer — the case that dropped characters.
        listOf("r", "ra", "rad", "radi", "radio").forEach { vm.setSearchQuery(it) }

        assertThat(vm.searchQuery.value).isEqualTo("radio")
    }

    @Test fun `clearing the query is immediate too`() = runTest {
        val vm = buildVm()

        vm.setSearchQuery("radiohead")
        // Asserted before clearing so this test can't pass vacuously: without the
        // fix the flow is never fed and "" == "" would satisfy the check below.
        assertThat(vm.searchQuery.value).isEqualTo("radiohead")

        vm.setSearchQuery("")

        assertThat(vm.searchQuery.value).isEmpty()
    }

    private fun buildVm(): LibraryViewModel {
        val musicRepository: MusicRepository = mock {
            on { getAllTracks() } doReturn flowOf(emptyList())
            on { getAllPlaylists() } doReturn flowOf(emptyList())
            on { getAllArtists() } doReturn flowOf(emptyList())
            on { getAllAlbums() } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
            on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
            on { getPlaylistsByType(eq(PlaylistType.STASH_LIKED)) } doReturn flowOf(emptyList())
            on { getPlaylistsByType(eq(PlaylistType.LIKED_SONGS)) } doReturn flowOf(emptyList())
        }
        return LibraryViewModel(
            musicRepository = musicRepository,
            playerRepository = mock { on { playerState } doReturn MutableStateFlow(PlayerState()) },
            tokenManager = mock {
                on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
                on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
            },
            playlistImageHelper = mock(),
            localImportCoordinator = mock { on { state } doReturn MutableStateFlow<LocalImportState>(LocalImportState.Idle) },
            streamingPreference = mock { on { enabled } doReturn flowOf(false) },
            flacUpgradeEnqueuer = mock(),
            ytMusicApiClient = mock(),
            libraryPreferencesStore = mock {
                onBlocking { getSortOrder() } doReturn SortOrder.RECENT
                onBlocking { getSourceFilter() } doReturn SourceFilter.ALL
            },
            artistImageDao = mock { on { observeAll() } doReturn flowOf(emptyList()) },
        )
    }
}
