package com.stash.data.ytmusic.model

/**
 * A track (song/video) from YouTube Music.
 *
 * @property videoId       The YouTube video ID (used for playback and matching).
 * @property title         The track title.
 * @property artists       Comma-separated artist names.
 * @property album         The album name, if available.
 * @property durationMs    Track duration in milliseconds, if known.
 * @property thumbnailUrl  URL of the track's thumbnail image.
 * @property musicVideoType YouTube Music's authoritative classification of the
 *   underlying video (ATV / OMV / UGC / OFFICIAL_SOURCE_MUSIC / PODCAST_EPISODE).
 *   Surfaced from InnerTube's `watchEndpointMusicConfig.musicVideoType` field;
 *   null when the renderer omits the field. This is the signal that Mode B
 *   canonicalization uses to decide whether a YT-library import needs to be
 *   reconciled to an ATV equivalent.
 */
data class YTMusicTrack(
    val videoId: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val musicVideoType: MusicVideoType? = null,
)

/**
 * A playlist from YouTube Music.
 *
 * @property playlistId   The YouTube Music playlist ID.
 * @property title        The playlist title.
 * @property thumbnailUrl URL of the playlist's thumbnail image.
 * @property trackCount   Number of tracks in the playlist, if known.
 */
data class YTMusicPlaylist(
    val playlistId: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val trackCount: Int? = null,
)

/**
 * Result of paginating a track-bearing browse endpoint (Liked Songs, a
 * playlist, a mix). Carries the partial-fetch signal + the playlist
 * header's reported track count so the worker can compare and surface
 * "fetched 1247/2000" diagnostics.
 *
 * @property tracks         All tracks accumulated across pages.
 * @property expectedCount  The playlist header's reported track count, if
 *                          parseable. Null when the response has no header
 *                          (Liked Songs has none) or the count couldn't be
 *                          parsed.
 * @property partial        True if any page failed after retries OR the
 *                          MAX_PAGES safety cap was hit OR fetched count
 *                          fell below 95% of [expectedCount].
 * @property partialReason  Human-readable explanation when [partial] is
 *                          true; null otherwise.
 */
data class PagedTracks(
    val tracks: List<YTMusicTrack>,
    val expectedCount: Int? = null,
    val partial: Boolean = false,
    val partialReason: String? = null,
)

/**
 * Result of paginating the user-library playlist list. No expectedCount —
 * the library page does not publish a total.
 */
data class PagedPlaylists(
    val playlists: List<YTMusicPlaylist>,
    val partial: Boolean = false,
    val partialReason: String? = null,
)
