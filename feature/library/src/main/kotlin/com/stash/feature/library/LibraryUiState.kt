package com.stash.feature.library

import com.stash.core.model.Playlist
import com.stash.core.model.Track

/**
 * UI state for the Library screen.
 *
 * Holds every piece of data that the composable tree needs to render,
 * including the active tab, search/sort state, and the four content lists.
 */
data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.PLAYLISTS,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.RECENT,
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<ArtistInfo> = emptyList(),
    val albums: List<AlbumInfo> = emptyList(),
    val isLoading: Boolean = true,
)

/** Tabs available in the library browser. */
enum class LibraryTab { PLAYLISTS, TRACKS, ARTISTS, ALBUMS }

/** Sort options applicable to every content tab. */
enum class SortOrder { RECENT, ALPHABETICAL, MOST_PLAYED }

/**
 * Lightweight projection of an artist for the Artists tab.
 *
 * @property name          Display name of the artist.
 * @property trackCount    Number of tracks by this artist in the library.
 * @property totalDurationMs Combined duration of all tracks in milliseconds.
 */
data class ArtistInfo(
    val name: String,
    val trackCount: Int,
    val totalDurationMs: Long,
)

/**
 * Lightweight projection of an album for the Albums tab.
 *
 * @property name      Album title.
 * @property artist    Primary artist on the album.
 * @property trackCount Number of tracks in this album.
 * @property artPath   Local file path to album artwork, or null.
 */
data class AlbumInfo(
    val name: String,
    val artist: String,
    val trackCount: Int,
    val artPath: String?,
)
