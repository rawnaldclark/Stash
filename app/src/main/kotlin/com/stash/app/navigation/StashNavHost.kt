package com.stash.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stash.feature.home.HomeScreen
import com.stash.feature.home.PlaylistBrowseScreen
import com.stash.feature.library.AlbumDetailScreen
import com.stash.feature.library.ArtistDetailScreen
import com.stash.feature.library.LibraryScreen
import com.stash.feature.library.LikedSongsDetailScreen
import com.stash.feature.library.PlaylistDetailScreen
import com.stash.feature.library.mixbuilder.MixBuilderScreen
import com.stash.feature.nowplaying.NowPlayingScreen
import com.stash.feature.search.AlbumDiscoveryScreen
import com.stash.feature.search.ArtistProfileScreen
import com.stash.feature.search.SearchScreen
import com.stash.feature.settings.BlockedSongsScreen
import com.stash.feature.settings.SettingsHubScreen
import com.stash.feature.settings.equalizer.EqualizerScreen
import com.stash.feature.settings.libraryhealth.LibraryHealthScreen
import com.stash.feature.sync.FailedDownloadsScreen
import com.stash.feature.sync.FailedMatchesScreen
import com.stash.feature.sync.SyncScreen

/** Transition duration for the Now Playing slide animation in milliseconds. */
private const val SLIDE_DURATION_MS = 350

/**
 * Main navigation host for the Stash app.
 *
 * Contains all top-level tab destinations plus the full-screen Now Playing
 * route which enters with a slide-up and exits with a slide-down transition.
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
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
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
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(PlaylistDetailRoute(playlistId))
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
                        ),
                    )
                },
                onSeeAllPlaylists = { genre ->
                    navController.navigate(PlaylistBrowseRoute(genre))
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
        composable<MixBuilderRoute> {
            MixBuilderScreen(onBack = { navController.popBackStack() })
        }
        composable<LibraryRoute> {
            LibraryScreen(
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(PlaylistDetailRoute(playlistId))
                },
                onNavigateToArtist = { artistName ->
                    navController.navigate(ArtistDetailRoute(artistName))
                },
                onNavigateToAlbum = { albumName, artistName ->
                    navController.navigate(AlbumDetailRoute(albumName, artistName))
                },
                onNavigateToLikedSongs = { source ->
                    navController.navigate(LikedSongsDetailRoute(source))
                },
                onNavigateToMixBuilder = { recipeId ->
                    navController.navigate(MixBuilderRoute(recipeId))
                },
                onSelectionModeChanged = onSelectionModeChanged,
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
                onNavigateToSettings = {
                    navController.navigate(SettingsRoute) {
                        launchSingleTop = true
                    }
                },
            )
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
                onDonate = { runCatching { uriHandler.openUri("https://ko-fi.com/rawnald") } },
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
                onNavigateToArcodConnect = { navController.navigate(ArcodConnectRoute) },
                viewModel = viewModel,
            )
        }
        composable<SettingsAccountsRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAccountsScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        composable<SettingsLibraryStorageRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsLibraryStorageScreen(
                onBack = { navController.popBackStack() },
                onNavigateToLibraryHealth = { navController.navigate(LibraryHealthRoute) },
                viewModel = viewModel,
            )
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

        composable<ArcodConnectRoute> { backStackEntry ->
            // Reuse the Settings-scoped ViewModel so the harvested Supabase
            // session writes to the same ArcodCredentialStore the source +
            // interceptor read from, and survives this route's dispose.
            val settingsEntry = remember(backStackEntry) {
                navController.getBackStackEntry(SettingsRoute)
            }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.components.ArcodConnectScreen(
                onConnected = viewModel::onArcodConnected,
                onClose = { navController.popBackStack() },
            )
        }

        composable<EqualizerRoute> {
            EqualizerScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<LibraryHealthRoute> {
            LibraryHealthScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<DiagnosticsPreviewRoute> {
            com.stash.feature.settings.diagnostics.DiagnosticsPreviewScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<BlockedSongsRoute> {
            BlockedSongsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<PlaylistDetailRoute> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
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
            FailedMatchesScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<FailedDownloadsRoute> {
            FailedDownloadsScreen(onBack = { navController.popBackStack() })
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

        composable<NowPlayingRoute>(
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
        ) {
            NowPlayingScreen(
                onDismiss = { navController.popBackStack() },
                onNavigateToArtist = { id, name, avatar, focusAlbum ->
                    navController.navigate(SearchArtistRoute(id, name, avatar, focusAlbum))
                },
            )
        }
    }
}
