package com.stash.app.navigation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stash.feature.home.HomeScreen
import com.stash.feature.library.LibraryScreen
import com.stash.feature.settings.SettingsScreen
import com.stash.feature.sync.SyncScreen

@Composable
fun StashNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> { HomeScreen() }
        composable<LibraryRoute> { LibraryScreen() }
        composable<SyncRoute> { SyncScreen() }
        composable<SettingsRoute> { SettingsScreen() }
    }
}
