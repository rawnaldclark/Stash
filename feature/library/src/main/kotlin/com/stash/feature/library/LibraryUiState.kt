package com.stash.feature.library

import com.stash.core.model.Playlist
import com.stash.core.model.Track

/**
 * UI state for the Library screen.
 *
 * Artists are split into multi-track (primary) and single-track (collapsed) lists so the UI can
 * show the main library prominently and hide the noise from daily-mix one-off entries behind an
 * expandable section. Albums are saved albums only, so they need no such split.
 */
data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.TRACKS,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.RECENT,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),

    /** Recently downloaded tracks — feeds the Songs recently-downloaded rail. */
    val recentlyAdded: List<Track> = emptyList(),

    /** Artists with 2+ tracks, sorted by track count descending. */
    val artists: List<ArtistInfo> = emptyList(),
    /** Artists with exactly 1 track, collapsed by default. */
    val singleTrackArtists: List<ArtistInfo> = emptyList(),

    /** Saved albums only, one card per album (see [AlbumInfo]). */
    val albums: List<AlbumInfo> = emptyList(),

    val isLoading: Boolean = true,
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    val currentlyPlayingTrackId: Long? = null,

    /** Total downloaded songs in the library (unfiltered) — drives the Shuffle hero count. */
    val librarySongCount: Int = 0,

    /** The library holds cloud storage songs: the Cloud filter is offered (hidden otherwise). */
    val hasCloudSongs: Boolean = false,

    /** Stream-only mode: Songs shows online rows too, and the Downloaded/Online filter is offered. */
    val streamingMode: Boolean = false,

    /**
     * Stream only's "In your library" choice for Artists: they, their counts and the pages they
     * open list only the songs Songs lists. Always false in Download, which ignores it. (Albums
     * are saved albums, all in the library already.)
     */
    val libraryOnly: Boolean = false,
)

/** Tabs available in the library browser. LIKED sits between Songs and Playlists. */
enum class LibraryTab { PLAYLISTS, TRACKS, LIKED, ARTISTS, ALBUMS, DEVICE }

/** Source sift for the Liked subcategory: all likes, or one origin. */
enum class LikedFilter { ALL, STASH, SPOTIFY, YOUTUBE, SOUNDCLOUD }

/** Sort options applicable to every content tab. DURATION is offered for Songs (and Artists). */
enum class SortOrder { RECENT, ALPHABETICAL, MOST_PLAYED, DURATION }

/**
 * Top-level filter applied to the Tracks tab. Originally just service
 * source (Spotify / YouTube); [FLAC] piggybacks on the same chip row
 * because the user-facing question is the same — "show me some subset
 * of my tracks". When selected, only lossless-codec files (flac, alac,
 * wav, etc.) survive. [NON_FLAC] is the inverse — downloaded tracks
 * still on a lossy codec, i.e. the batch-upgrade worklist.
 * [DOWNLOADED] and [ONLINE] split by whether the file is on this phone;
 * they're offered only in Stream-only mode.
 */
enum class SourceFilter {
    ALL, YOUTUBE, SPOTIFY, FLAC, NON_FLAC, DOWNLOADED, ONLINE,

    /** Songs from the user's own cloud storage (Dropbox / OneDrive); offered only when there are some. */
    CLOUD,
}

/**
 * @property name           Display name of the artist.
 * @property trackCount     Songs the artist's page lists (see [buildArtistCards]): every song
 *                          crediting them in Stream only, the downloaded ones in Download.
 * @property totalDurationMs Combined duration of those songs in milliseconds.
 * @property artUrl         Remote artwork URL (album art proxy from their top track).
 * @property downloadedCount What "Delete All by Artist" removes and its dialog names: the
 *                          downloaded songs whose credit leads with this artist. 0 hides it.
 */
data class ArtistInfo(
    val name: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val artUrl: String? = null,
    val photoUrl: String? = null,
    val downloadedCount: Int = 0,
)

/**
 * One Library › Albums card: a saved album ([com.stash.core.data.album.SavedAlbum]), never songs
 * grouped by their album text. Keyed by [playlistId], the saved album's own row: its page lists
 * every song in album order.
 *
 * @property artist      The act most of its songs credit first.
 * @property trackCount  Its songs.
 * @property dateAdded   When it was saved (epoch millis): Recently added sorts by it.
 * @property onPhoneCount Download mode, while its download is still running: how many of its
 *                       songs are on the phone ("9 of 14 on this phone"). Null otherwise.
 */
data class AlbumInfo(
    val playlistId: Long,
    val name: String,
    val artist: String,
    val trackCount: Int,
    val artUrl: String? = null,
    val dateAdded: Long = 0L,
    val onPhoneCount: Int? = null,
)
