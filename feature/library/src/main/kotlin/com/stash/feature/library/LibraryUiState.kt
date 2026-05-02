package com.stash.feature.library

import com.stash.core.model.Playlist
import com.stash.core.model.Track

/**
 * UI state for the Library screen.
 *
 * Artists and albums are split into multi-track (primary) and single-track
 * (collapsed) lists so the UI can show the main library prominently and
 * hide the noise from daily-mix one-off entries behind an expandable section.
 */
data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.PLAYLISTS,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.RECENT,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),

    /** Artists with 2+ tracks, sorted by track count descending. */
    val artists: List<ArtistInfo> = emptyList(),
    /** Artists with exactly 1 track, collapsed by default. */
    val singleTrackArtists: List<ArtistInfo> = emptyList(),

    /** Albums with 2+ tracks, sorted by track count descending. */
    val albums: List<AlbumInfo> = emptyList(),
    /** Albums with exactly 1 track, collapsed by default. */
    val singleTrackAlbums: List<AlbumInfo> = emptyList(),

    val isLoading: Boolean = true,
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    val currentlyPlayingTrackId: Long? = null,
)

/** Tabs available in the library browser. */
enum class LibraryTab { PLAYLISTS, TRACKS, ARTISTS, ALBUMS }

/** Sort options applicable to every content tab. */
enum class SortOrder { RECENT, ALPHABETICAL, MOST_PLAYED }

/**
 * Top-level filter applied to the Tracks tab. Originally just service
 * source (Spotify / YouTube); [FLAC] piggybacks on the same chip row
 * because the user-facing question is the same — "show me some subset
 * of my tracks". When selected, only lossless-codec files (flac, alac,
 * wav, etc.) survive.
 */
enum class SourceFilter { ALL, YOUTUBE, SPOTIFY, FLAC }

/**
 * @property name           Display name of the artist.
 * @property trackCount     Number of tracks by this artist in the library.
 * @property totalDurationMs Combined duration of all tracks in milliseconds.
 * @property artUrl         Remote artwork URL (album art proxy from their top track).
 */
data class ArtistInfo(
    val name: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val artUrl: String? = null,
)

/**
 * @property name       Album title.
 * @property artist     Primary artist on the album.
 * @property trackCount Number of tracks in this album.
 * @property artPath    Local file path to album artwork, or null.
 * @property artUrl     Remote artwork URL, or null.
 */
data class AlbumInfo(
    val name: String,
    val artist: String,
    val trackCount: Int,
    val artPath: String? = null,
    val artUrl: String? = null,
)
