package com.stash.core.model

data class Track(
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String? = null,
    val fileFormat: String = "opus",
    val qualityKbps: Int = 0,
    val fileSizeBytes: Long = 0,
    val source: MusicSource = MusicSource.SPOTIFY,
    val spotifyUri: String? = null,
    val youtubeId: String? = null,
    val albumArtUrl: String? = null,
    val albumArtPath: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    val isDownloaded: Boolean = false,
    val matchConfidence: Float = 0f,
    val matchDismissed: Boolean = false,
    /**
     * International Standard Recording Code — Spotify's per-master unique
     * identifier. Null for YouTube-sourced tracks and for legacy Spotify
     * rows inserted before the matcher started requesting it. Used as the
     * highest-precision signal when matching to canonical YouTube uploads.
     */
    val isrc: String? = null,
    /**
     * Spotify's parental-advisory flag. Null for YouTube-sourced tracks and
     * legacy rows; true/false for Spotify rows synced post-v12. Matcher
     * prefers candidates whose explicitness matches the source.
     */
    val explicit: Boolean? = null,
)
