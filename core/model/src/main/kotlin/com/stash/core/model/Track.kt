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
)
