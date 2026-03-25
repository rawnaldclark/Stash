package com.stash.data.ytmusic.model

/**
 * A track (song/video) from YouTube Music.
 *
 * @property videoId     The YouTube video ID (used for playback and matching).
 * @property title       The track title.
 * @property artists     Comma-separated artist names.
 * @property album       The album name, if available.
 * @property durationMs  Track duration in milliseconds, if known.
 * @property thumbnailUrl URL of the track's thumbnail image.
 */
data class YTMusicTrack(
    val videoId: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
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
