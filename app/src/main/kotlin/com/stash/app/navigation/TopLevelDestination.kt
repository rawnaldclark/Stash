package com.stash.app.navigation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

enum class TopLevelDestination(val selectedIcon: ImageVector, val unselectedIcon: ImageVector, val label: String, val route: Any) {
    HOME(Icons.Filled.Home, Icons.Outlined.Home, "Home", HomeRoute),
    LIBRARY(Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, "Library", LibraryRoute),
    SEARCH(Icons.Filled.Search, Icons.Outlined.Search, "Search", SearchRoute),
    SYNC(Icons.Filled.Sync, Icons.Outlined.Sync, "Sync", SyncRoute),
    SETTINGS(Icons.Filled.Settings, Icons.Outlined.Settings, "Settings", SettingsRoute),
}

@Serializable data object HomeRoute
@Serializable data object LibraryRoute
@Serializable data object SearchRoute
@Serializable data object SyncRoute
@Serializable data object SettingsRoute
@Serializable data object NowPlayingRoute
@Serializable data class PlaylistDetailRoute(val playlistId: Long)
@Serializable data class ArtistDetailRoute(val artistName: String)
@Serializable data class AlbumDetailRoute(val albumName: String, val artistName: String)
@Serializable data class LikedSongsDetailRoute(val source: String? = null)
@Serializable data object FailedMatchesRoute

@Serializable
data class SearchArtistRoute(
    val artistId: String,
    val name: String,
    val avatarUrl: String? = null,
)
