package com.stash.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Failed Matches (unmatched songs) screen.
 *
 * @property tracks List of tracks that could not be matched on YouTube.
 * @property isLoading True while the initial data load is in progress.
 */
data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * ViewModel for the Failed Matches screen.
 *
 * Observes unmatched tracks from the repository and exposes them as a
 * [StateFlow]. Provides a [dismissTrack] action that permanently removes
 * a track from future sync retry attempts.
 */
@HiltViewModel
class FailedMatchesViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
) : ViewModel() {

    val uiState: StateFlow<FailedMatchesUiState> =
        musicRepository.getUnmatchedTracks()
            .map { tracks ->
                FailedMatchesUiState(tracks = tracks, isLoading = false)
            }
            .stateIn(
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
}
