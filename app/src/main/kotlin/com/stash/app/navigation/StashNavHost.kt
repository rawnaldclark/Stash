package com.stash.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.LocalBottomChromeInset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.stash.feature.community.CommunityHostViewModel
import com.stash.feature.community.CommunityPostScreen
import com.stash.feature.community.CommunityPosting
import com.stash.feature.community.CommunityScreen
import com.stash.feature.community.CommunitySection
import com.stash.feature.community.rememberCommunityPosting
import com.stash.feature.home.HomeScreen
import com.stash.feature.listening.YourListeningSection
import com.stash.core.common.primaryArtist
import com.stash.feature.listening.history.HistoryScreen
import com.stash.feature.listening.recap.RecapScreen
import com.stash.feature.listening.stats.ListeningStatsScreen
import com.stash.feature.listening.top.TopScreen
import com.stash.feature.home.MixBrowseScreen
import com.stash.feature.home.MixRail
import com.stash.feature.home.PlaylistBrowseScreen
import com.stash.feature.library.AlbumDetailScreen
import com.stash.feature.library.ArtistDetailScreen
import com.stash.feature.library.LibraryScreen
import com.stash.feature.library.LikedSongsDetailScreen
import com.stash.feature.library.PlaylistDetailScreen
import com.stash.feature.library.mixbuilder.MixBuilderScreen
import com.stash.feature.nowplaying.sheet.LocalPlayerSheet
import com.stash.feature.nowplaying.sheet.PlayerSheetAnchor
import com.stash.feature.search.AlbumDiscoveryScreen
import com.stash.feature.search.ArtistProfileScreen
import com.stash.feature.search.SearchScreen
import com.stash.feature.settings.BlockedSongsScreen
import com.stash.feature.settings.SettingsHubScreen
import com.stash.feature.settings.equalizer.EqualizerScreen
import com.stash.feature.settings.libraryhealth.LibraryHealthScreen
import com.stash.feature.sync.FailedDownloadsScreen
import com.stash.feature.sync.DownloadManagementScreen
import com.stash.feature.sync.FailedMatchesScreen
import com.stash.feature.sync.ManagePlaylistsScreen
import com.stash.feature.sync.SyncScreen

/**
 * Main navigation host for the Stash app: the tab destinations and their detail screens.
 *
 * Now Playing is not a destination any more: it is the player sheet over the NavHost
 * (StashScaffold's PlayerSheetOverlay). [NowPlayingRoute] survives as a shim for back stacks
 * saved by older builds.
 */
@Composable
fun StashNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    // Forwarded to detail screens that support multi-select so the host can hide
    // the mini-player while a screen is in selection mode. General by design:
    // the same lambda will be wired to Liked/Album/Artist/Library detail screens
    // in later tasks — only the Playlist destination consumes it today.
    onSelectionModeChanged: (Boolean) -> Unit = {},
    // The scaffold's canonical bottom-nav tab switch, for screens that trigger
    // one themselves (Home's Liked card → Library ▸ Liked).
    onNavigateToTab: (TopLevelDestination) -> Unit = {},
    // Open Stash on (#428). Fixed for the activity's life; MainActivity saves it across recreation.
    startDestination: Any = HomeRoute,
    // Stash Community (spec 2026-09-26 §3): the scaffold hosts the posting sheets and
    // LocalPostToCommunity over every screen and the player; the screens here open the picker.
    posting: CommunityPosting = rememberCommunityPosting(),
) {
    val host: CommunityHostViewModel = hiltViewModel()
    // Community turned off (spec §3: nothing Community anywhere): a tab can restore a Community screen it saved
    // while it was on, so leave any that's showing. null is "not read yet": a restored one stays until the switch is
    // read, so a cold start with Community on doesn't lose it.
    val communityOn by host.on.collectAsStateWithLifecycle()
    val top by navController.currentBackStackEntryAsState()
    LaunchedEffect(communityOn, top) {
        if (communityOn == false) {
            while (navController.currentDestination?.let { it.hasRoute<CommunityRoute>() || it.hasRoute<CommunityPostRoute>() } == true &&
                navController.popBackStack()) Unit
        }
    }
    StashNavGraph(navController, modifier, onSelectionModeChanged, onNavigateToTab, posting, startDestination)
}

/**
 * For a destination not laid out under the glass bottom chrome (a form, a web view, a screen
 * with its own bottom-pinned controls, or one rarely visited): kept above the chrome as every
 * screen was before edge to edge, so nothing of it is ever hidden. Its children read a zero
 * [LocalBottomChromeInset], so a shared component that pads by it doesn't pad twice.
 */
@Composable
private fun AboveChrome(content: @Composable () -> Unit) {
    val inset = LocalBottomChromeInset.current
    Box(Modifier.fillMaxSize().padding(bottom = inset)) {
        CompositionLocalProvider(LocalBottomChromeInset provides 0.dp, content = content)
    }
}

@Composable
private fun StashNavGraph(
    navController: NavHostController,
    modifier: Modifier,
    onSelectionModeChanged: (Boolean) -> Unit,
    onNavigateToTab: (TopLevelDestination) -> Unit,
    posting: CommunityPosting,
    startDestination: Any,
) {
    val playerSheet = LocalPlayerSheet.current
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onNavigateToSettings = {
                    navController.navigate(SettingsRoute) {
                        // Clear top so repeated taps don't stack Settings entries.
                        launchSingleTop = true
                    }
                },
                // Home's lossless-offline banner routes through onNavigateToSettings
                // (Settings › Audio), where every lossless path — your Qobuz account,
                // a relay endpoint — is set up.
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(PlaylistDetailRoute(playlistId))
                },
                onShareMix = { id -> navController.navigate(PlaylistDetailRoute(id, openShare = true)) },
                // Liked card: the ViewModel queued the Liked focus; perform the
                // canonical tab switch so Back + bottom-bar state behave exactly
                // like tapping the Library tab — then pop any RESTORED detail
                // screens so LibraryScreen actually composes and consumes the
                // focus now (restoreState can resurrect a playlist detail on
                // top, which would swallow the tap and fire the jump on a later
                // Back). No-op when the restored stack is just Library root.
                onNavigateToLibrary = {
                    onNavigateToTab(TopLevelDestination.LIBRARY)
                    navController.popBackStack(LibraryRoute, inclusive = false)
                },
                // Qobuz discovery album/playlist taps → the shared album-detail
                // screen (playlists ride the same route via QOBUZ_PLAYLIST source).
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                            source = album.source,
                            qobuzTrackCount = album.trackCount,
                        ),
                    )
                },
                onSeeAllPlaylists = { genre ->
                    navController.navigate(PlaylistBrowseRoute(genre))
                },
                onNavigateToMixBuilder = { recipeId ->
                    navController.navigate(MixBuilderRoute(recipeId))
                },
                onSeeAllMixes = { rail ->
                    navController.navigate(MixBrowseRoute(rail.name))
                },
                // Report an issue = diagnostics preview first, GitHub from there.
                onReportIssue = { navController.navigate(DiagnosticsPreviewRoute) },
                communitySection = {
                    CommunitySection(
                        onOpenPost = { id -> navController.navigate(CommunityPostRoute(id)) },
                        onSeeAll = { navController.navigate(CommunityRoute()) },
                        onPost = posting.pick,
                    )
                },
                yourListeningSection = {
                    YourListeningSection(
                        onOpenHistory = { navController.navigate(ListeningHistoryRoute()) },
                        onOpenStats = { range -> navController.navigate(YourListeningRoute(range)) },
                        onOpenTop = { kind, range -> navController.navigate(TopRoute(kind, range)) },
                        onOpenRecap = { kind, year, month ->
                    // A double tap opens one story, not two (the second's cleanup would end the first's music).
                    navController.navigate(RecapRoute(kind, year, month)) { launchSingleTop = true }
                },
                    )
                },
            )
        }
        composable<PlaylistBrowseRoute> {
            PlaylistBrowseScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                            source = album.source,
                        ),
                    )
                },
            )
        }
        composable<MixBrowseRoute> {
            MixBrowseScreen(
                rail = MixRail.valueOf(it.toRoute<MixBrowseRoute>().rail),
                onBack = { navController.popBackStack() },
                onOpenMix = { id -> navController.navigate(PlaylistDetailRoute(id)) },
            )
        }
        composable<MixBuilderRoute> {
            AboveChrome {
                MixBuilderScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<LibraryRoute> {
            LibraryScreen(
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(PlaylistDetailRoute(playlistId))
                },
                onNavigateToArtist = { artistName ->
                    navController.navigate(ArtistDetailRoute(artistName))
                },
                onNavigateToAlbum = { _, name, _, artistName ->
                    navController.navigate(AlbumDetailRoute(name, artistName))
                },
                onNavigateToRemoteAlbum = { albumId, name, artUrl, artistName ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = albumId,
                            title = name,
                            artist = artistName,
                            thumbnailUrl = artUrl,
                            year = null,
                            source = com.stash.data.ytmusic.model.AlbumSource.YOUTUBE,
                        ),
                    )
                },
                onSelectionModeChanged = onSelectionModeChanged,
                // The empty library's buttons: real tab switches (Back and the tab bar behave
                // as if tapped), and Accounts the way Sync's Re-authenticate opens it.
                onNavigateToSync = { onNavigateToTab(TopLevelDestination.SYNC) },
                onNavigateToSearch = { onNavigateToTab(TopLevelDestination.SEARCH) },
                onNavigateToAccounts = {
                    navController.navigate(SettingsRoute) {
                        launchSingleTop = true
                    }
                    navController.navigate(SettingsAccountsRoute) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                onNavigateToArtist = { id, name, avatar ->
                    navController.navigate(SearchArtistRoute(id, name, avatar))
                },
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                            source = album.source,
                        ),
                    )
                },
            )
        }
        composable<SyncRoute> {
            SyncScreen(
                onNavigateToFailedMatches = {
                    navController.navigate(FailedMatchesRoute)
                },
                // Phase 8: Library actions (Blocked Songs + Fix wrong-version)
                // moved out of Settings into the Sync tab's Library section.
                onNavigateToBlockedSongs = { navController.navigate(BlockedSongsRoute) },
                onNavigateToFailedDownloads = {
                    navController.navigate(FailedDownloadsRoute)
                },
                onNavigateToDownloads = {
                    navController.navigate(DownloadManagementRoute)
                },
                // "Re-authenticate" opens Accounts itself, where the expired card
                // leads with Reconnect, instead of the Settings hub one tap short.
                // Settings goes under it first: Accounts shares the ViewModel
                // scoped to the SettingsRoute entry (getBackStackEntry throws
                // without it), as the captcha deep link does. Back from Accounts
                // lands on Settings, then Sync.
                onNavigateToAccounts = {
                    navController.navigate(SettingsRoute) {
                        launchSingleTop = true
                    }
                    navController.navigate(SettingsAccountsRoute) {
                        launchSingleTop = true
                    }
                },
                onManageSource = { source -> navController.navigate(ManagePlaylistsRoute(source.name)) },
            )
        }
        composable<ManagePlaylistsRoute> {
            AboveChrome {
                ManagePlaylistsScreen(
                    source = com.stash.feature.sync.SyncSource.valueOf(it.toRoute<ManagePlaylistsRoute>().source),
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<SettingsRoute> {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            SettingsHubScreen(
                onOpenPlayback = { navController.navigate(SettingsPlaybackRoute) },
                onOpenAudioQuality = { navController.navigate(SettingsAudioQualityRoute) },
                onOpenAccounts = { navController.navigate(SettingsAccountsRoute) },
                onOpenLibraryStorage = { navController.navigate(SettingsLibraryStorageRoute) },
                onOpenAppearance = { navController.navigate(SettingsAppearanceRoute) },
                onOpenAbout = { navController.navigate(SettingsAboutRoute) },
                onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                onStar = { runCatching { uriHandler.openUri("https://github.com/rawnaldclark/Stash") } },
            )
        }

        composable<SettingsPlaybackRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsPlaybackScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        composable<SettingsAudioQualityRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAudioQualityScreen(
                onBack = { navController.popBackStack() },
                onNavigateToEqualizer = { navController.navigate(EqualizerRoute) },
                onNavigateToSquidWtfCaptcha = { navController.navigate(SquidWtfCaptchaRoute) },
                viewModel = viewModel,
            )
        }
        composable<SettingsAccountsRoute> { backStackEntry ->
            // Its Spotify / YouTube Music / Discord sign-in WebViews replace the whole screen, and
            // their pages pin buttons to the bottom (Google's Next): kept above the chrome.
            AboveChrome {
                val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
                val viewModel: com.stash.feature.settings.SettingsViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
                com.stash.feature.settings.SettingsAccountsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }
        }
        composable<SettingsLibraryStorageRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsLibraryStorageScreen(
                onBack = { navController.popBackStack() },
                onNavigateToLibraryHealth = { navController.navigate(LibraryHealthRoute) },
                viewModel = viewModel,
                onNavigateToWebLink = if (com.stash.app.BuildConfig.WEB_LINK_ENABLED) {
                    { navController.navigate(WebLinkRoute()) }
                } else {
                    null
                },
            )
        }
        composable<WebLinkRoute> {
            // Its QR scanner pins a button to the bottom edge: kept above the chrome.
            AboveChrome {
                com.stash.feature.settings.weblink.SettingsWebLinkScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<SettingsAppearanceRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAppearanceScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        composable<SettingsAboutRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAboutScreen(
                onBack = { navController.popBackStack() },
                onNavigateToDiagnosticsPreview = { navController.navigate(DiagnosticsPreviewRoute) },
                viewModel = viewModel,
            )
        }

        composable<SquidWtfCaptchaRoute> { backStackEntry ->
            AboveChrome {
                // Reach the Settings ViewModel from the parent route so the
                // captured cookie value writes to the same DataStore the
                // interceptor reads from. We grab the *Settings* nav entry
                // (not this one) so the ViewModel survives this route's
                // dispose and the value lands in prefs immediately.
                val settingsEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(SettingsRoute)
                }
                val viewModel: com.stash.feature.settings.SettingsViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
                com.stash.feature.settings.components.SquidWtfCaptchaScreen(
                    onCookieCaptured = viewModel::onSquidWtfCaptchaCookieChanged,
                    onClose = { navController.popBackStack() },
                )
            }
        }

        composable<EqualizerRoute> {
            AboveChrome {
                EqualizerScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }

        composable<LibraryHealthRoute> {
            AboveChrome {
                LibraryHealthScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }

        composable<DiagnosticsPreviewRoute> {
            AboveChrome {
                com.stash.feature.settings.diagnostics.DiagnosticsPreviewScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }

        composable<BlockedSongsRoute> {
            AboveChrome {
                BlockedSongsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable<PlaylistDetailRoute> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<SharedMixRoute> {
            AboveChrome {
                com.stash.feature.library.share.SharedMixScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { id -> navController.navigate(PlaylistDetailRoute(id)) },
                    // Follow / Save a copy land on the new playlist; Back skips the mix screen.
                    onJoinedPlaylist = { id ->
                        navController.navigate(PlaylistDetailRoute(id)) { popUpTo<SharedMixRoute> { inclusive = true } }
                    },
                )
            }
        }

        composable<SharedTrackRoute> {
            AboveChrome {
                com.stash.feature.library.share.SharedTrackScreen(onBack = { navController.popBackStack() })
            }
        }

        composable<CommunityRoute> {
            CommunityScreen(
                mine = it.toRoute<CommunityRoute>().mine,
                onBack = { navController.popBackStack() },
                onOpenPost = { id -> navController.navigate(CommunityPostRoute(id)) },
                onPost = posting.pick,
            )
        }

        composable<CommunityPostRoute> { entry ->
            // Save a copy opens the new playlist (Back returns to the post), and a take-down closes the post. Both run
            // from a ViewModel coroutine, so each acts only while this entry is the top of a live back stack: a
            // take-down's reply after Back (the ViewModel lives through the 700 ms fade) would otherwise pop the screen
            // below too (from Home: Home itself, a blank screen), and a callback left over from before a rotation holds
            // the old NavController, whose entry is DESTROYED (navigate() on it throws, so a finished save would read
            // as failed). CommunityPostViewModel reads the route's `postId` from SavedStateHandle by that name.
            val onTop = {
                navController.currentBackStackEntry === entry && entry.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
            }
            CommunityPostScreen(
                onBack = { if (onTop()) navController.popBackStack() },
                onOpenPlaylist = { id -> if (onTop()) navController.navigate(PlaylistDetailRoute(id)) },
                onOpenPost = { id -> if (onTop()) navController.navigate(CommunityPostRoute(id)) },
                onOpenArtist = { id, name, avatar -> if (onTop()) navController.navigate(SearchArtistRoute(id, name, avatar)) },
            )
        }

        composable<ListeningHistoryRoute> {
            HistoryScreen(onBack = { navController.popBackStack() })
        }

        composable<YourListeningRoute> {
            ListeningStatsScreen(
                onBack = { navController.popBackStack() },
                onOpenHistory = { day -> navController.navigate(ListeningHistoryRoute(day ?: -1L)) },
                onOpenTop = { kind, range -> navController.navigate(TopRoute(kind, range)) },
                onOpenArtist = { name -> navController.navigate(ArtistDetailRoute(name.primaryArtist())) },
                onOpenAlbum = { album, artist -> navController.navigate(AlbumDetailRoute(album, artist)) },
                onOpenRecap = { kind, year, month ->
                    // A double tap opens one story, not two (the second's cleanup would end the first's music).
                    navController.navigate(RecapRoute(kind, year, month)) { launchSingleTop = true }
                },
                onOpenLastFmRecap = { kind, year, month ->
                    navController.navigate(RecapRoute(kind, year, month, source = "lastfm")) { launchSingleTop = true }
                },
            )
        }

        // Full screen over everything (StashScaffold hides the bottom chrome on this route).
        composable<RecapRoute> { entry ->
            // Pops only while the story is the top entry: a close racing a swipe-down or "Play your
            // top songs" (two closes) must not pop Home as well.
            RecapScreen(
                onClose = { if (navController.currentBackStackEntry === entry) navController.popBackStack() },
                // A Last.fm recap with no account: the story gives way to Settings › Accounts.
                onOpenLastFmSettings = {
                    if (navController.currentBackStackEntry === entry) {
                        navController.popBackStack()
                        navController.navigate(SettingsAccountsRoute)
                    }
                },
            )
        }

        composable<TopRoute> {
            TopScreen(
                onBack = { navController.popBackStack() },
                onOpenArtist = { name -> navController.navigate(ArtistDetailRoute(name.primaryArtist())) },
                onOpenAlbum = { album, artist -> navController.navigate(AlbumDetailRoute(album, artist)) },
            )
        }

        composable<ListenJoinRoute> {
            AboveChrome {
                com.stash.feature.nowplaying.listen.JoinSessionScreen(
                    onBack = { navController.popBackStack() },
                    // After joining, Now Playing is the session: the Join screen goes and the player opens
                    // over the page that was under it.
                    onJoined = {
                        navController.popBackStack<ListenJoinRoute>(inclusive = true)
                        playerSheet?.expand()
                    },
                )
            }
        }

        composable<ArtistDetailRoute> {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<AlbumDetailRoute> {
            AlbumDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<LikedSongsDetailRoute> {
            LikedSongsDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<FailedMatchesRoute> {
            AboveChrome {
                FailedMatchesScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable<FailedDownloadsRoute> {
            AboveChrome {
                FailedDownloadsScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<DownloadManagementRoute> {
            AboveChrome {
                DownloadManagementScreen(onBack = { navController.popBackStack() })
            }
        }

        composable<SearchArtistRoute> {
            ArtistProfileScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                            source = album.source,
                            // Set on the Qobuz gap-fill albums, null on YouTube's.
                            qobuzTrackCount = album.trackCount,
                        ),
                    )
                },
                onNavigateToArtist = { id, name, avatar ->
                    navController.navigate(SearchArtistRoute(id, name, avatar))
                },
            )
        }

        composable<SearchAlbumRoute> {
            AlbumDiscoveryScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                            source = album.source,
                        ),
                    )
                },
            )
        }

        // A back stack saved by an older build can still end on Now Playing: drop the entry and
        // open the player sheet instead, unanimated. (Remove two releases after the sheet ships.)
        composable<NowPlayingRoute> {
            LaunchedEffect(Unit) {
                navController.popBackStack(NowPlayingRoute, inclusive = true)
                playerSheet?.snapTo(PlayerSheetAnchor.FULL)
            }
        }
    }
}
