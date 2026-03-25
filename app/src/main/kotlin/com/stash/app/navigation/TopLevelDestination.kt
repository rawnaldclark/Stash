package com.stash.app.navigation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

enum class TopLevelDestination(val selectedIcon: ImageVector, val unselectedIcon: ImageVector, val label: String, val route: Any) {
    HOME(Icons.Filled.Home, Icons.Outlined.Home, "Home", HomeRoute),
    LIBRARY(Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, "Library", LibraryRoute),
    SYNC(Icons.Filled.Sync, Icons.Outlined.Sync, "Sync", SyncRoute),
    SETTINGS(Icons.Filled.Settings, Icons.Outlined.Settings, "Settings", SettingsRoute),
}

@Serializable data object HomeRoute
@Serializable data object LibraryRoute
@Serializable data object SyncRoute
@Serializable data object SettingsRoute
@Serializable data object NowPlayingRoute
