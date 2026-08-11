package com.stash.core.model

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    /**
     * `true` when the active track is being streamed rather than read from
     * local storage — drives the Now Playing wifi/streaming indicator.
     * True when the MediaItem's URI scheme is `http`/`https` (kennyy/squid/
     * youtube) OR it carries a stream origin (antra, which plays its FLAC
     * from a local `file://` cache file but is still a stream). Downloaded
     * tracks play `file://` with no stream origin and read as not-streaming.
     * Computed in `PlayerRepositoryImpl.computeIsStreaming` on every refresh.
     */
    val isStreaming: Boolean = false,
    /**
     * `true` while the active track is loading and not yet playable —
     * either ExoPlayer is in `STATE_BUFFERING`, or `setQueue` is still
     * resolving a tapped track's stream URL (the YouTube-fallback yt-dlp
     * resolve takes ~11 s, during which no MediaItem is set yet so the
     * player isn't buffering). Drives the play/pause spinner so a slow
     * resolve doesn't look frozen.
     */
    val isBuffering: Boolean = false,
    /** What this queue is playing FROM (a playlist, artist, library shuffle,
     *  radio station, etc.) — shown in Now Playing and persisted for resume. */
    val source: PlaybackSource = PlaybackSource.Unknown,
)

enum class RepeatMode {
    OFF, ALL, ONE
}
