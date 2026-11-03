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
    /**
     * `true` while the player *intends* to play: Media3's `playWhenReady`, reported false in
     * STATE_IDLE / STATE_ENDED where nothing will play until a new `play()`. Unlike [isPlaying] it
     * stays true through buffering (a stall, the 403 → yt-dlp re-resolve), which is what a
     * play/pause toggle must key on: toggling on [isPlaying] sent a tap during buffering to
     * `play()` (a no-op, playWhenReady was already true), so the user could not pause until the
     * stream recovered.
     */
    val playWhenReady: Boolean = false,
    /** What this queue is playing FROM (a playlist, artist, library shuffle,
     *  radio station, etc.) — shown in Now Playing and persisted for resume. */
    val source: PlaybackSource = PlaybackSource.Unknown,
    /**
     * With shuffle on: row i of [queue] (the play order) is timeline slot `shuffleTimelineSlots[i]`, so the
     * unshuffled order is the rows sorted by slot. Null when shuffle is off. Handoff publishes it as `original`.
     */
    val shuffleTimelineSlots: List<Int>? = null,
    /**
     * A queue too big for the player (a whole library) is held as a window of [queue]: these are
     * its songs before and after that window, in play order. Both 0 for every other queue, which
     * [queue] holds whole. "12 of 72,593" is `queueSongsBefore + currentIndex + 1` of
     * `queueSongsBefore + queue.size + queueSongsAfter`.
     */
    val queueSongsBefore: Int = 0,
    val queueSongsAfter: Int = 0,
    /**
     * The playing song's place in such a queue's whole order (0-based), or -1 for every other queue.
     * Equal to `queueSongsBefore + currentIndex` except after a repeat-all wrap, where the window
     * holds the end of the order and its beginning and no song is "before" it.
     */
    val queuePosition: Int = -1,
)

enum class RepeatMode {
    OFF, ALL, ONE
}
