package com.stash.feature.home.shelves

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.ytmusic.model.MoodShelf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What a mood's page shows: its name, then a spinner, YouTube's rows of playlists, or a retry; in Download mode
 * ([offline]), only the way back to Stream only, since every playlist on it streams.
 */
data class MoodUiState(
    val title: String,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val offline: Boolean = false,
    val shelves: List<MoodShelf> = emptyList(),
)

/**
 * A mood's page ([MoodScreen]): reads the page of the tile it was opened from ([ARG_PARAMS], named [ARG_TITLE]
 * until the page's own header names it) through [MoodsRepository.page], once, and again on [retry]. Follows the
 * Download switch like Home's shelves: in Download mode it reads nothing and says so (it's reachable from the back
 * stack after the switch), and turning Stream only back on loads it.
 */
@HiltViewModel
class MoodViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val moods: MoodsRepository,
    streamingPreference: StreamingPreference,
) : ViewModel() {

    private val params: String = requireNotNull(savedStateHandle[ARG_PARAMS]) { "MoodRoute requires params" }
    private val title: String = savedStateHandle[ARG_TITLE] ?: ""

    private val _state = MutableStateFlow(MoodUiState(title = title))
    val state: StateFlow<MoodUiState> = _state.asStateFlow()

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            streamingPreference.enabled.distinctUntilChanged().collect { streaming ->
                if (!streaming) {
                    loading?.cancel()
                    _state.update { it.copy(offline = true, loading = false, failed = false) }
                } else {
                    _state.update { it.copy(offline = false) }
                    // Back from Download mode, or the first look: read the page unless it's on screen already
                    if (_state.value.shelves.isEmpty()) retry()
                }
            }
        }
    }

    /** The page didn't load: ask again. Nothing in Download mode, where the page stays closed. */
    fun retry() {
        if (loading?.isActive == true || _state.value.offline) return
        _state.update { it.copy(loading = true, failed = false) }
        load()
    }

    private fun load() {
        loading = viewModelScope.launch {
            try {
                val page = moods.page(params, title)
                _state.value = MoodUiState(title = page.title.ifBlank { title }, loading = false, shelves = page.shelves)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "mood '$title' didn't load: ${e.message}")
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    companion object {
        private const val TAG = "Mood"

        /** Nav arguments, by the route's property names. */
        const val ARG_PARAMS = "params"
        const val ARG_TITLE = "title"
    }
}
