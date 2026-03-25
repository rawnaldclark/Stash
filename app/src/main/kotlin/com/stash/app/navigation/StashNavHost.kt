package com.stash.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stash.feature.home.HomeScreen
import com.stash.feature.library.LibraryScreen
import com.stash.feature.nowplaying.NowPlayingScreen
import com.stash.feature.settings.SettingsScreen
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
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> { HomeScreen() }
        composable<LibraryRoute> { LibraryScreen() }
        composable<SyncRoute> { SyncScreen() }
        composable<SettingsRoute> { SettingsScreen() }

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
            )
        }
    }
}
