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
@Serializable data object FailedDownloadsRoute
@Serializable data object BlockedSongsRoute
@Serializable data object EqualizerRoute
@Serializable data object LibraryHealthRoute
@Serializable data object SquidWtfCaptchaRoute
@Serializable data object ArcodConnectRoute
@Serializable data object DiagnosticsPreviewRoute
@Serializable data object SettingsPlaybackRoute
@Serializable data object SettingsAudioQualityRoute
@Serializable data object SettingsAccountsRoute
@Serializable data object SettingsLibraryStorageRoute
@Serializable data object SettingsAppearanceRoute
@Serializable data object SettingsAboutRoute
@Serializable data class MixBuilderRoute(val recipeId: Long? = null)

@Serializable
data class SearchArtistRoute(
    val artistId: String,
    val name: String,
    val avatarUrl: String? = null,
    val focusAlbum: String? = null,
)

@Serializable
data class SearchAlbumRoute(
    val browseId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
    // Which catalog this album came from — routes AlbumCache + the play path.
    // Defaulted so any pre-update back-stack entry still deserializes.
    val source: com.stash.data.ytmusic.model.AlbumSource =
        com.stash.data.ytmusic.model.AlbumSource.YOUTUBE,
)

/** "See all" Qobuz-playlist browse, filtered by the [genre] chip label ("All" = none). */
@Serializable
data class PlaylistBrowseRoute(val genre: String = "All")

/** "See all" browse of one Home mix rail; [rail] is a [MixRail] name. */
@Serializable
data class MixBrowseRoute(val rail: String)

/** Full-screen per-source playlist management; [source] is a [SyncSource] name. */
@Serializable
data class ManagePlaylistsRoute(val source: String)
