package com.stash.feature.nowplaying

import androidx.compose.ui.graphics.Color
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track

/**
 * Immutable snapshot of everything the Now Playing screen needs to render.
 *
 * Mapped from [com.stash.core.model.PlayerState] plus position ticks and
 * palette-extracted colors from the album art.
 */
data class NowPlayingUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isBuffering: Boolean = false,
    val queueSize: Int = 0,
    val currentIndex: Int = 0,
    /** Dominant color extracted from the album art via Palette API. */
    val dominantColor: Color = Color(0xFF6750A4),
    /** Vibrant color extracted from the album art via Palette API. */
    val vibrantColor: Color = Color(0xFF8E24AA),
    /** Muted color extracted from the album art via Palette API. */
    val mutedColor: Color = Color(0xFF37474F),
) {

    /**
     * Playback progress as a fraction in `[0f, 1f]`.
     * Returns `0f` when duration is zero to avoid division-by-zero.
     */
    val progressFraction: Float
        get() = if (durationMs > 0) {
            (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

    /** `true` when a track is loaded and ready to display. */
    val hasTrack: Boolean
        get() = currentTrack != null
}
