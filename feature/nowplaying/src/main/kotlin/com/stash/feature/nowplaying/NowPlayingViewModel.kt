package com.stash.feature.nowplaying

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import com.stash.core.media.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the full-screen Now Playing screen.
 *
 * Observes [PlayerRepository.playerState] and [PlayerRepository.currentPosition],
 * maps them into a single [NowPlayingUiState], and exposes one-shot action functions
 * that delegate to the repository.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    init {
        observePlayerState()
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    /**
     * Combines the player state snapshot with the high-frequency position
     * ticker into a single [NowPlayingUiState] emission.
     */
    private fun observePlayerState() {
        combine(
            playerRepository.playerState,
            playerRepository.currentPosition,
        ) { state, positionMs ->
            _uiState.update { current ->
                current.copy(
                    currentTrack = state.currentTrack,
                    isPlaying = state.isPlaying,
                    currentPositionMs = positionMs,
                    durationMs = state.durationMs,
                    shuffleEnabled = state.isShuffleEnabled,
                    repeatMode = state.repeatMode,
                    queueSize = state.queue.size,
                    currentIndex = state.currentIndex,
                    queue = state.queue,
                )
            }
        }.launchIn(viewModelScope)
    }

    // ------------------------------------------------------------------
    // User Actions
    // ------------------------------------------------------------------

    /** Toggle between play and pause. */
    fun onPlayPauseClick() {
        viewModelScope.launch {
            if (_uiState.value.isPlaying) {
                playerRepository.pause()
            } else {
                playerRepository.play()
            }
        }
    }

    /** Advance to the next track in the queue. */
    fun onSkipNext() {
        viewModelScope.launch { playerRepository.skipNext() }
    }

    /** Return to the previous track (or restart current). */
    fun onSkipPrevious() {
        viewModelScope.launch { playerRepository.skipPrevious() }
    }

    /**
     * Seek to [positionMs] within the current track.
     *
     * @param positionMs target position in milliseconds, clamped to `[0, durationMs]`.
     */
    fun onSeekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, _uiState.value.durationMs)
        viewModelScope.launch { playerRepository.seekTo(clamped) }
    }

    /** Toggle shuffle mode on / off. */
    fun onToggleShuffle() {
        viewModelScope.launch { playerRepository.toggleShuffle() }
    }

    /** Cycle repeat mode: OFF -> ALL -> ONE -> OFF. */
    fun onCycleRepeatMode() {
        viewModelScope.launch { playerRepository.cycleRepeatMode() }
    }

    /**
     * Remove the track at [index] from the playback queue.
     * The currently-playing track cannot be removed through this action.
     */
    fun onRemoveFromQueue(index: Int) {
        if (index == _uiState.value.currentIndex) return
        viewModelScope.launch { playerRepository.removeFromQueue(index) }
    }

    /**
     * Move a track within the queue from position [from] to position [to].
     */
    fun onMoveInQueue(from: Int, to: Int) {
        viewModelScope.launch { playerRepository.moveInQueue(from, to) }
    }

    /**
     * Jump playback to the track at [index] in the queue.
     */
    fun onSkipToQueueIndex(index: Int) {
        viewModelScope.launch { playerRepository.skipToQueueIndex(index) }
    }

    // ------------------------------------------------------------------
    // Palette Color Extraction
    // ------------------------------------------------------------------

    /**
     * Called when the album art [Bitmap] has been loaded (e.g. via Coil).
     *
     * Extracts dominant, vibrant, and muted colors on [Dispatchers.Default]
     * so the main thread is never blocked by the Palette computation.
     *
     * Passing `null` resets colors to their defaults.
     */
    fun onAlbumArtLoaded(bitmap: Bitmap?) {
        if (bitmap == null) {
            _uiState.update {
                it.copy(
                    dominantColor = Color(0xFF6750A4),
                    vibrantColor = Color(0xFF8E24AA),
                    mutedColor = Color(0xFF37474F),
                )
            }
            return
        }

        viewModelScope.launch {
            val (dominant, vibrant, muted) = withContext(Dispatchers.Default) {
                val palette = Palette.from(bitmap).generate()
                Triple(
                    Color(palette.getDominantColor(0xFF6750A4.toInt())),
                    Color(palette.getVibrantColor(0xFF8E24AA.toInt())),
                    Color(palette.getMutedColor(0xFF37474F.toInt())),
                )
            }
            _uiState.update {
                it.copy(
                    dominantColor = dominant,
                    vibrantColor = vibrant,
                    mutedColor = muted,
                )
            }
        }
    }
}
