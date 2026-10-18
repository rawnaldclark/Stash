package com.stash.feature.nowplaying

import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.ui.components.PlaylistInfo

/**
 * Immutable snapshot of everything the Now Playing screen needs to render.
 *
 * Mapped from [com.stash.core.model.PlayerState]. The album colours are not here: the screen
 * reads them from LocalAlbumChrome, the one app-wide palette the mini player wears too.
 */
data class NowPlayingUiState(
    val currentTrack: Track? = null,
    /**
     * [currentTrack] is a library row (Room answered for it), so its lyrics are stored and its
     * sync offset kept. False for a stream playing under a transient id until a listen persists
     * it. Null for the moment after a track change before Room has answered: unknown, not "no".
     */
    val currentTrackStored: Boolean? = null,
    val isPlaying: Boolean = false,
    // No playback position here, deliberately: it ticks 4x/s, and as a field of this state it
    // recomposed all of Now Playing and the app-wide MiniPlayer on every tick. Readers take
    // NowPlayingViewModel.positionMs and read it as late as they can (draw, or a small child).
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isBuffering: Boolean = false,
    /**
     * The player means to play (see [com.stash.core.model.PlayerState.playWhenReady]): true
     * through buffering, where [isPlaying] is false. The play/pause toggle and its icon key on
     * this, so a stalled stream can always be paused.
     */
    val playWhenReady: Boolean = false,
    val queueSize: Int = 0,
    val currentIndex: Int = 0,
    /** Full list of tracks in the playback queue (including the currently playing track). */
    val queue: List<Track> = emptyList(),
    /** User-created playlists available for the "Save to Playlist" sheet. */
    val userPlaylists: List<PlaylistInfo> = emptyList(),
    /**
     * `true` when the currently-playing MediaItem is sourced from an
     * `http`/`https` URI (i.e. streamed from Kennyy) rather than a
     * local `file://` URI. Used by the Now Playing screen to render
     * a small wifi indicator on the quality line.
     */
    val isStreaming: Boolean = false,
    /** What the current queue is playing from — shown in the queue sheet header. */
    val source: PlaybackSource = PlaybackSource.Unknown,
) {

    /** `true` when a track is loaded and ready to display. */
    val hasTrack: Boolean
        get() = currentTrack != null
}

/**
 * Playback progress as a fraction in `[0f, 1f]`.
 * Returns `0f` when duration is zero to avoid division-by-zero.
 */
internal fun progressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
