package com.stash.feature.library

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.prefs.LibraryScopePreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the batch-action contract added in Task 11 of the multi-select redesign
 * for the Library (Tracks tab). Each batch method wraps the existing
 * single-track path — Queue uses the batch [PlayerRepository.addToQueue]
 * overload (single call), Play Next loops [PlayerRepository.addNext], and
 * download/remove/save/delete loop the per-id [MusicRepository] calls with a
 * single roll-up Snackbar and per-item failure isolation.
 *
 * Mirrors the harness in [PlaylistDetailViewModelTest] /
 * [LikedSongsDetailViewModelTest]: StandardTestDispatcher +
 * Dispatchers.setMain/resetMain, runTest{}, mockito-kotlin, runCurrent().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test fun `connected SoundCloud likes exclude another accounts retained likes`() = runTest {
        val own = com.stash.core.model.Playlist(id = 7, name = "SoundCloud Liked", source = com.stash.core.model.MusicSource.SOUNDCLOUD,
            sourceId = "soundcloud_liked_7", type = com.stash.core.model.PlaylistType.LIKED_SONGS, trackCount = 1)
        val previous = own.copy(id = 8, sourceId = "soundcloud_liked_8")
        val repo = musicRepoMock().stub {
            on { getPlaylistsByType(com.stash.core.model.PlaylistType.LIKED_SONGS) } doReturn flowOf(listOf(previous, own))
            on { getPlaylistsByType(com.stash.core.model.PlaylistType.STASH_LIKED) } doReturn flowOf(emptyList())
            on { getShownTracksByPlaylist(7) } doReturn flowOf(listOf(track(70)))
            on { getShownTracksByPlaylist(8) } doReturn flowOf(listOf(track(80)))
        }
        val tokens = tokenManagerMock().stub {
            on { soundCloudSession } doReturn flowOf(com.stash.core.auth.soundcloud.SoundCloudSession("7", "Current", connectionId = "current"))
        }
        val vm = buildVm(musicRepository = repo, tokenManager = tokens)
        val likes = kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5000) { vm.likedTracks.first { it.isNotEmpty() } }
        }
        assertEquals(listOf(70L), likes.map { it.id })
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun playSelectedNext_loops_addNext_per_track() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(playerRepository = playerRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        vm.playSelectedNext(tracks)
        runCurrent()

        tracks.forEach { t -> verify(playerRepo).addNext(t) }
    }

    @Test
    fun addSelectedToQueue_uses_batch_overload() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(playerRepository = playerRepo)
        val tracks = listOf(track(1L), track(2L))

        vm.addSelectedToQueue(tracks)
        runCurrent()

        // batch overload, single call
        verify(playerRepo).addToQueue(tracks)
    }

    @Test
    fun downloadSelected_queues_each_id_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        ids.forEach { id -> verify(musicRepo).queueDownload(id) }
        assertEquals(listOf("Queued 3 songs for download."), messages)
    }

    @Test
    fun removeDownloadsForSelected_removes_each_id_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L)

        val messages = collectMessages(vm)
        vm.removeDownloadsForSelected(ids)
        runCurrent()

        ids.forEach { id -> verify(musicRepo).removeDownload(id) }
        assertEquals(listOf("Removed downloads for 2 songs."), messages)
    }

    @Test
    fun saveSelectedToPlaylist_adds_each_id_to_target() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L)
        val targetPlaylistId = 99L

        vm.saveSelectedToPlaylist(ids, targetPlaylistId)
        runCurrent()

        verify(musicRepo).addTracksToPlaylist(ids, targetPlaylistId)
    }

    @Test
    fun createPlaylistAndAddTracks_creates_once_then_adds_each_to_new_id() = runTest {
        val musicRepo = musicRepoMock()
        val newPlaylistId = 99L
        whenever(musicRepo.createPlaylistWithTracks(eq("My Mix"), any())).thenReturn(newPlaylistId)
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        vm.createPlaylistAndAddTracks("My Mix", ids)
        runCurrent()

        verify(musicRepo).createPlaylistWithTracks("My Mix", ids)
    }

    @Test
    fun deleteSelected_deletes_each_track_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = false)
        runCurrent()

        tracks.forEach { t -> verify(musicRepo).deleteTrack(t) }
        assertEquals(listOf("Deleted 3 songs."), messages)
    }

    @Test
    fun deleteSelected_with_blacklist_blacklists_each_id() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = true)
        runCurrent()

        tracks.forEach { t -> verify(musicRepo).blacklistTrack(t.id) }
        assertEquals(listOf("Deleted 2 songs."), messages)
    }

    @Test
    fun downloadSelected_isolates_per_item_failure() = runTest {
        val musicRepo = musicRepoMock()
        // Second item throws; first and third must still be attempted.
        whenever(musicRepo.queueDownload(eq(2L)))
            .thenThrow(RuntimeException("boom"))
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        // All three repo calls happened despite the middle one throwing.
        verify(musicRepo).queueDownload(1L)
        verify(musicRepo).queueDownload(2L)
        verify(musicRepo).queueDownload(3L)
        // Roll-up reflects only the two that succeeded.
        assertEquals(listOf("Queued 2 songs for download."), messages)
    }

    @Test
    fun downloadSelected_counts_only_true_queue_results() = runTest {
        val musicRepo = musicRepoMock()
        whenever(musicRepo.queueDownload(eq(2L))).thenReturn(false)
        val vm = buildVm(musicRepository = musicRepo)

        val messages = collectMessages(vm)
        vm.downloadSelected(listOf(1L, 2L, 3L))
        runCurrent()

        assertEquals(listOf("Queued 2 songs for download."), messages)
    }

    // ------------------------------------------------------------------
    // Artist and album cards: what they play follows the page and the connection
    // ------------------------------------------------------------------

    /**
     * What an artist or album page lists in Stream only: songs 1 and 3 downloaded, 2 and 4 not.
     * (In Download the repository lists only 1 and 3; see [downloadModeSongs].)
     */
    private val streamOnlySongs = listOf(
        Track(1L, "One", "Drake", album = "Views", isDownloaded = true, filePath = "/a"),
        Track(2L, "Two", "Drake", album = "Views"),
        Track(3L, "Three", "Drake", album = "Views", isDownloaded = true, filePath = "/c"),
        Track(4L, "Four", "Drake", album = "Views"),
    )
    private val downloadModeSongs = streamOnlySongs.filter { it.isDownloaded }

    private fun pageRepo(songs: List<Track>): MusicRepository = musicRepoMock().stub {
        on { getTracksByArtist("Drake") } doReturn flowOf(songs)
        on { getTracksByAlbum("Views", "Drake") } doReturn flowOf(songs)
    }

    private fun connectivity(online: Boolean): ConnectivityMonitor = mock { on { isConnected() } doReturn online }

    @Test
    fun stream_only_online_playArtist_plays_every_song_the_page_lists() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(musicRepository = pageRepo(streamOnlySongs), playerRepository = playerRepo, connectivityMonitor = connectivity(true))

        vm.playArtist("Drake")
        runCurrent()

        verify(playerRepo).setQueue(streamOnlySongs, 0, source = com.stash.core.model.PlaybackSource.Artist("Drake"))
    }

    @Test
    fun stream_only_offline_playArtist_plays_only_the_downloaded_songs() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(musicRepository = pageRepo(streamOnlySongs), playerRepository = playerRepo, connectivityMonitor = connectivity(false))

        vm.playArtist("Drake")
        runCurrent()

        verify(playerRepo).setQueue(downloadModeSongs, 0, source = com.stash.core.model.PlaybackSource.Artist("Drake"))
    }

    @Test
    fun download_mode_playArtist_plays_the_downloaded_songs_online_and_offline() = runTest {
        for (online in listOf(true, false)) {
            val playerRepo = playerRepoMock()
            val vm = buildVm(musicRepository = pageRepo(downloadModeSongs), playerRepository = playerRepo, connectivityMonitor = connectivity(online))

            vm.playArtist("Drake")
            runCurrent()

            verify(playerRepo).setQueue(downloadModeSongs, 0, source = com.stash.core.model.PlaybackSource.Artist("Drake"))
        }
    }

    @Test
    fun offline_playArtist_with_nothing_downloaded_says_why() = runTest {
        val playerRepo = playerRepoMock()
        val notDownloaded = streamOnlySongs.filterNot { it.isDownloaded }
        val vm = buildVm(musicRepository = pageRepo(notDownloaded), playerRepository = playerRepo, connectivityMonitor = connectivity(false))
        val messages = collectMessages(vm)

        vm.playArtist("Drake")
        runCurrent()

        verify(playerRepo, org.mockito.kotlin.never()).setQueue(any(), any(), any())
        assertEquals(listOf("None of this artist's songs are downloaded. Connect to play them."), messages)
    }

    @Test
    fun addArtistToQueue_appends_what_play_would_play_in_one_batch() = runTest {
        for ((online, expected) in listOf(true to streamOnlySongs, false to downloadModeSongs)) {
            val playerRepo = playerRepoMock()
            val vm = buildVm(musicRepository = pageRepo(streamOnlySongs), playerRepository = playerRepo, connectivityMonitor = connectivity(online))

            vm.addArtistToQueue("Drake")
            runCurrent()

            verify(playerRepo).addToQueue(expected)
        }
    }

    @Test
    fun playAlbum_plays_what_the_album_page_lists_under_the_same_rule() = runTest {
        for ((online, expected) in listOf(true to streamOnlySongs, false to downloadModeSongs)) {
            val playerRepo = playerRepoMock()
            val vm = buildVm(musicRepository = pageRepo(streamOnlySongs), playerRepository = playerRepo, connectivityMonitor = connectivity(online))

            vm.playAlbum("Views", "Drake")
            runCurrent()

            verify(playerRepo).setQueue(expected, 0, source = com.stash.core.model.PlaybackSource.Album("Views", "Drake"))
        }
    }

    @Test
    fun addAlbumToQueue_appends_what_play_would_play_in_one_batch() = runTest {
        for ((online, expected) in listOf(true to streamOnlySongs, false to downloadModeSongs)) {
            val playerRepo = playerRepoMock()
            val vm = buildVm(musicRepository = pageRepo(streamOnlySongs), playerRepository = playerRepo, connectivityMonitor = connectivity(online))

            vm.addAlbumToQueue("Views", "Drake")
            runCurrent()

            verify(playerRepo).addToQueue(expected)
        }
    }

    @Test
    fun offline_playAlbum_with_nothing_downloaded_says_why() = runTest {
        val playerRepo = playerRepoMock()
        val notDownloaded = streamOnlySongs.filterNot { it.isDownloaded }
        val vm = buildVm(musicRepository = pageRepo(notDownloaded), playerRepository = playerRepo, connectivityMonitor = connectivity(false))
        val messages = collectMessages(vm)

        vm.playAlbum("Views", "Drake")
        runCurrent()

        verify(playerRepo, org.mockito.kotlin.never()).setQueue(any(), any(), any())
        assertEquals(listOf("None of this album's songs are downloaded. Connect to play them."), messages)
    }

    // ------------------------------------------------------------------
    // The Artists grid counts what the artist page lists
    // ------------------------------------------------------------------

    @Test
    fun artist_card_count_matches_the_artist_page_in_both_modes() = runTest {
        // Drake: two own songs (one downloaded), a downloaded collab, a feature on Future's song
        // that isn't downloaded, and a compilation credited to him as album artist.
        val songs = listOf(
            Track(1L, "One", "Drake", isDownloaded = true, filePath = "/a"),
            Track(2L, "Two", "Drake"),
            Track(3L, "Three", "Drake, 21 Savage", isDownloaded = true, filePath = "/c"),
            Track(4L, "Four", "Future, Drake"),
            Track(5L, "Five", "Someone Else", albumArtist = "Drake", isDownloaded = true, filePath = "/e"),
        )
        for (streamOnly in listOf(false, true)) {
            val listed = songs.filter { streamOnly || it.isDownloaded }
            // What TrackDao.getAllArtists returns for those rows: one per credit pair.
            val pairs = listed.groupBy { it.artist to it.albumArtist }.map { (key, group) ->
                com.stash.core.data.db.dao.ArtistSummary(
                    artist = key.first,
                    trackCount = group.size,
                    totalDurationMs = 0,
                    artUrl = null,
                    albumArtist = key.second,
                    downloadedCount = group.count { it.isDownloaded },
                )
            }
            // What the artist page lists (MusicRepository.getTracksByArtist).
            val page = listed.filter { com.stash.core.common.matchesArtistCredits(it.artist, it.albumArtist, "Drake") }
            val musicRepo = musicRepoMock().stub {
                on { getAllArtists() } doReturn flowOf(pairs)
                on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
            }
            val vm = buildVm(musicRepository = musicRepo)

            val state = vm.uiState.first { !it.isLoading && (it.artists + it.singleTrackArtists).isNotEmpty() }
            val drake = (state.artists + state.singleTrackArtists).single { it.name == "Drake" }

            assertEquals("streamOnly=$streamOnly", page.size, drake.trackCount)
            // Its delete names only the downloaded songs he leads: 1 and 3.
            assertEquals("streamOnly=$streamOnly", 2, drake.downloadedCount)
        }
    }

    @Test
    fun in_your_library_shows_in_Stream_only_and_Download_ignores_it() = runTest {
        // The repository narrows the rows (MusicRepositoryLibraryScopeTest); the state only tells
        // the sheet and the "Showing your library only" chip, which must stay hidden in Download.
        for (streamOnly in listOf(false, true)) {
            val musicRepo = musicRepoMock().stub {
                on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
                on { getLibraryTracksIncludingOnline() } doReturn flowOf(emptyList())
            }
            val vm = buildVm(
                musicRepository = musicRepo,
                streamingPreference = mock { on { enabled } doReturn flowOf(streamOnly) },
                libraryScopePreference = mock { on { inLibraryOnly } doReturn flowOf(true) },
            )

            val state = vm.uiState.first { !it.isLoading }

            assertEquals("streamOnly=$streamOnly", streamOnly, state.libraryOnly)
        }
    }

    @Test
    fun setLibraryOnly_saves_the_choice() = runTest {
        val pref = mock<LibraryScopePreference> { on { inLibraryOnly } doReturn flowOf(false) }
        val vm = buildVm(libraryScopePreference = pref)

        vm.setLibraryOnly(true)
        runCurrent()
        verify(pref).setInLibraryOnly(true)

        vm.setLibraryOnly(false)
        runCurrent()
        verify(pref).setInLibraryOnly(false)
    }

    @Test
    fun deleteArtist_deletes_only_the_downloaded_songs_on_the_card() = runTest {
        val own = Track(1L, "God's Plan", "Drake", isDownloaded = true, filePath = "/a")
        val ownCollab = Track(2L, "Jimmy Cooks", "Drake, 21 Savage", isDownloaded = true, filePath = "/b")
        // Under Future's card, not Drake's.
        val feature = Track(3L, "Life Is Good", "Future, Drake", isDownloaded = true, filePath = "/c")
        // A synced, streamable row: not on any Library card.
        val streamOnly = Track(4L, "Hotline Bling", "Drake", isDownloaded = false)
        // A compilation whose album artist is Drake: credit search matches it, the card doesn't.
        val compilation = Track(5L, "Signs", "Someone Else", albumArtist = "Drake", isDownloaded = true, filePath = "/d")
        val musicRepo = musicRepoMock()
        whenever(musicRepo.getTracksByArtist("Drake"))
            .thenReturn(flowOf(listOf(own, ownCollab, feature, streamOnly, compilation)))
        val vm = buildVm(musicRepository = musicRepo)
        val messages = collectMessages(vm)

        vm.deleteArtist("Drake")
        runCurrent()

        verify(musicRepo).deleteTrack(own)
        verify(musicRepo).deleteTrack(ownCollab)
        verify(musicRepo, org.mockito.kotlin.never()).deleteTrack(feature)
        verify(musicRepo, org.mockito.kotlin.never()).deleteTrack(streamOnly)
        verify(musicRepo, org.mockito.kotlin.never()).deleteTrack(compilation)
        assertEquals(listOf("Deleted 2 songs."), messages)
    }

    @Test
    fun deletePlaylist_reports_what_the_cascade_deleted_and_kept() = runTest {
        // The dialog said "all its tracks will be removed", and the cascade's summary (songs kept
        // because they're in Liked or another playlist) was never shown.
        val musicRepo = musicRepoMock().stub {
            onBlocking { deletePlaylistWithCascade(any(), any()) } doReturn
                MusicRepository.CascadeRemovalSummary(deleted = 9, keptProtected = 2, keptElsewhere = 1, blacklisted = 0)
        }
        val vm = buildVm(musicRepository = musicRepo)
        val messages = collectMessages(vm)

        vm.deletePlaylist(com.stash.core.model.Playlist(id = 5L, name = "Road trip", source = com.stash.core.model.MusicSource.SPOTIFY))
        runCurrent()

        assertEquals(listOf("Deleted 9 songs. Kept 3 songs that are in Liked Songs or other playlists."), messages)
    }

    @Test
    fun cascade_summary_message_covers_each_outcome() {
        fun summary(deleted: Int, kept: Int) =
            MusicRepository.CascadeRemovalSummary(deleted = deleted, keptProtected = kept, keptElsewhere = 0, blacklisted = 0)
        assertEquals("Deleted 1 song.", cascadeSummaryMessage(summary(1, 0)))
        assertEquals(
            "Playlist deleted. Its 4 songs stay: they're in Liked Songs or other playlists.",
            cascadeSummaryMessage(summary(0, 4)),
        )
        assertEquals("Playlist deleted.", cascadeSummaryMessage(summary(0, 0)))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun track(id: Long) = Track(id = id, title = "Track $id", artist = "Artist")

    /** Collects [LibraryViewModel.userMessages] into a list for the test. */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(
        vm: LibraryViewModel,
    ): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        runCurrent()
        return messages
    }

    private fun playerRepoMock(): PlayerRepository = mock {
        on { playerState } doReturn MutableStateFlow(PlayerState())
    }

    private fun musicRepoMock(): MusicRepository = mock {
        on { getAllTracks() } doReturn flowOf(emptyList())
        on { getAllPlaylists() } doReturn flowOf(emptyList())
        on { getAllArtists() } doReturn flowOf(emptyList())
        on { getAllAlbums() } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
        onBlocking { deleteTrack(any()) } doReturn true
    }

    private fun tokenManagerMock(): TokenManager = mock {
        on { soundCloudSession } doReturn flowOf(null)
        on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
        on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
    }

    private fun libraryPreferencesStoreMock(): LibraryPreferencesStore = mock {
        onBlocking { getSortOrder() } doReturn com.stash.feature.library.SortOrder.RECENT
        onBlocking { getSourceFilter() } doReturn com.stash.feature.library.SourceFilter.ALL
    }

    /**
     * Builds a [LibraryViewModel] for tests. All collaborators default to
     * plain mocks with the minimum stubs needed for the VM's `init`/`stateIn`
     * flows to start up without NPEs. Tests that care about a specific
     * collaborator pass in their own configured mock.
     */
    private fun buildVm(
        playerRepository: PlayerRepository = playerRepoMock(),
        musicRepository: MusicRepository = musicRepoMock(),
        tokenManager: TokenManager = tokenManagerMock(),
        playlistImageHelper: PlaylistImageHelper = mock(),
        localImportCoordinator: LocalImportCoordinator = mock {
            on { state } doReturn MutableStateFlow<LocalImportState>(LocalImportState.Idle)
        },
        streamingPreference: StreamingPreference = mock { on { enabled } doReturn flowOf(false) },
        libraryPreferencesStore: LibraryPreferencesStore = libraryPreferencesStoreMock(),
        artistImageDao: ArtistImageDao = mock { on { observeAll() } doReturn flowOf(emptyList()) },
        connectivityMonitor: ConnectivityMonitor? = null,
        libraryScopePreference: LibraryScopePreference? = null,
    ): LibraryViewModel = LibraryViewModel(
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        tokenManager = tokenManager,
        playlistImageHelper = playlistImageHelper,
        localImportCoordinator = localImportCoordinator,
        streamingPreference = streamingPreference,
        flacUpgradeEnqueuer = org.mockito.kotlin.mock(),
        ytMusicApiClient = org.mockito.kotlin.mock(),
        libraryPreferencesStore = libraryPreferencesStore,
        libraryDeepLinkController = com.stash.core.data.navigation.LibraryDeepLinkController(),
        artistImageDao = artistImageDao,
        sharedMixRepository = org.mockito.kotlin.mock { on { observeActiveFollowedIds() }.thenReturn(kotlinx.coroutines.flow.flowOf(emptyList())) },
        connectivityMonitor = connectivityMonitor,
        libraryScopePreference = libraryScopePreference,
    )
}
