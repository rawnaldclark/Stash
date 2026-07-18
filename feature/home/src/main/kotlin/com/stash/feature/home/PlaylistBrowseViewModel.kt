package com.stash.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.discovery.GenreCatalog
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.data.ytmusic.model.PlaylistSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Paginated "See all" browse of the Qobuz editorial playlist catalog (~6.3k),
 * filtered by the genre chip the user came from. Accumulates pages of
 * [PAGE_SIZE] on demand; a short page marks the end. Fail-soft — an empty page
 * (repository swallows errors) simply stops paging.
 *
 * A non-blank [PlaylistBrowseUiState.query] switches the SAME accumulation
 * into catalog-wide playlist search (debounced 300 ms; Qobuz search has no
 * genre filter). A generation counter discards pages that were in flight
 * when the query changed, so stale results never splice into a fresh list.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class PlaylistBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: HomeDiscoveryRepository,
) : ViewModel() {

    val genreLabel: String = savedStateHandle["genre"] ?: "All"
    private val genreId: Int? = GenreCatalog.idFor(genreLabel)

    private val _state = MutableStateFlow(PlaylistBrowseUiState())
    val state: StateFlow<PlaylistBrowseUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var inFlight = false
    private var generation = 0

    init {
        loadMore()
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .map { it.trim() }
                .distinctUntilChanged()
                .drop(1) // the initial "" — the first browse load is already running
                .collect {
                    generation++
                    inFlight = false
                    _state.update { it.copy(playlists = emptyList(), endReached = false) }
                    loadMore()
                }
        }
    }

    /** Immediate echo into the text field; the debounced flow drives loading. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queryFlow.value = query
    }

    /** Load the next page. No-op while a load is in flight or the end is reached. */
    fun loadMore() {
        if (inFlight || _state.value.endReached) return
        inFlight = true
        val gen = generation
        val query = queryFlow.value.trim()
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val offset = _state.value.playlists.size
            val page = if (query.isBlank()) {
                repository.browsePlaylists(genreId = genreId, offset = offset, limit = PAGE_SIZE)
            } else {
                repository.searchPlaylists(query = query, offset = offset, limit = PAGE_SIZE)
            }
            if (gen == generation) {
                _state.update {
                    it.copy(
                        playlists = it.playlists + page,
                        isLoading = false,
                        endReached = page.size < PAGE_SIZE,
                    )
                }
                inFlight = false
            }
            // Stale generation: drop the page silently — the reset already
            // cleared inFlight and kicked off the fresh load.
        }
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}

data class PlaylistBrowseUiState(
    val query: String = "",
    val playlists: List<PlaylistSummary> = emptyList(),
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
)
