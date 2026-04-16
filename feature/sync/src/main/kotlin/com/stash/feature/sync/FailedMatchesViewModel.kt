package com.stash.feature.sync

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.preview.PreviewUrlExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Failed Matches (unmatched songs) screen.
 *
 * @property tracks List of tracks that could not be matched on YouTube.
 * @property isLoading True while the initial data load is in progress.
 * @property previewLoading The videoId currently being loaded for preview, or null.
 */
data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val isLoading: Boolean = true,
    val previewLoading: String? = null,
)

/**
 * ViewModel for the Failed Matches screen.
 *
 * Observes unmatched tracks from the repository and exposes them as a
 * [StateFlow]. Provides a [dismissTrack] action that permanently removes
 * a track from future sync retry attempts, and audio preview for rejected
 * match candidates via [PreviewPlayer].
 */
@HiltViewModel
class FailedMatchesViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
) : ViewModel() {

    companion object {
        private const val TAG = "FailedMatchesVM"
    }

    /** Observable preview playback state for the UI to highlight the active row. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    private val _previewLoading = MutableStateFlow<String?>(null)

    val uiState: StateFlow<FailedMatchesUiState> =
        combine(
            musicRepository.getUnmatchedTracks(),
            _previewLoading,
        ) { tracks, loading ->
            FailedMatchesUiState(
                tracks = tracks,
                isLoading = false,
                previewLoading = loading,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FailedMatchesUiState(),
        )

    /**
     * Marks a track as dismissed so it will no longer be retried during sync.
     *
     * @param trackId The ID of the track to dismiss.
     */
    fun dismissTrack(trackId: Long) {
        viewModelScope.launch {
            musicRepository.dismissMatch(trackId)
        }
    }

    /**
     * Starts an audio preview for the closest rejected YouTube match.
     *
     * Stops any currently playing preview first, then extracts a direct stream
     * URL via [PreviewUrlExtractor] and hands it to [PreviewPlayer].
     *
     * @param videoId The YouTube video ID of the rejected candidate.
     */
    fun previewRejectedMatch(videoId: String) {
        previewPlayer.stop()
        viewModelScope.launch {
            _previewLoading.value = videoId
            try {
                val url = previewUrlExtractor.extractStreamUrl(videoId)
                previewPlayer.playUrl(videoId, url)
            } catch (e: Exception) {
                Log.e(TAG, "Preview failed for videoId=$videoId", e)
            }
            _previewLoading.value = null
        }
    }

    /** Stops the current audio preview, if any. */
    fun stopPreview() {
        previewPlayer.stop()
        _previewLoading.value = null
    }

    override fun onCleared() {
        super.onCleared()
        previewPlayer.stop()
    }
}
