package com.stash.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.discovery.GenreCatalog
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.data.ytmusic.model.PlaylistSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Paginated "See all" browse of the Qobuz editorial playlist catalog (~6.3k),
 * filtered by the genre chip the user came from. Accumulates pages of
 * [PAGE_SIZE] on demand; a short page marks the end. Fail-soft — an empty page
 * (repository swallows errors) simply stops paging.
 */
@HiltViewModel
class PlaylistBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: HomeDiscoveryRepository,
) : ViewModel() {

    val genreLabel: String = savedStateHandle["genre"] ?: "All"
    private val genreId: Int? = GenreCatalog.idFor(genreLabel)

    private val _state = MutableStateFlow(PlaylistBrowseUiState())
    val state: StateFlow<PlaylistBrowseUiState> = _state.asStateFlow()

    private var inFlight = false

    init { loadMore() }

    /** Load the next page. No-op while a load is in flight or the end is reached. */
    fun loadMore() {
        if (inFlight || _state.value.endReached) return
        inFlight = true
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val page = repository.browsePlaylists(
                genreId = genreId,
                offset = _state.value.playlists.size,
                limit = PAGE_SIZE,
            )
            _state.update {
                it.copy(
                    playlists = it.playlists + page,
                    isLoading = false,
                    endReached = page.size < PAGE_SIZE,
                )
            }
            inFlight = false
        }
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}

data class PlaylistBrowseUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
)
