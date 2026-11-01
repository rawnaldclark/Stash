package com.stash.feature.home.shelves

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.home.NewFromArtistsRepository
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.ytmusic.model.AlbumSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What Home's own streaming shelves show. Every list is empty in Offline mode (the app's Download mode plays only
 * what's on the phone, and these shelves are streaming content), so Home renders nothing for them there.
 */
data class HomeShelvesState(
    /** "New from your artists": album and single cards, newest first. */
    val newFromArtists: List<AlbumSummary> = emptyList(),
)

/**
 * Home's own shelves, apart from [com.stash.feature.home.HomeViewModel] (which carries the Discover hero, the synced
 * mixes and the Qobuz rows): "New from your artists". Each is built by its repository at most once a day and saved
 * to a file; this asks for it when Home is first shown, again on each return to Home (a file read until the day
 * turns), and when Offline mode is switched off. Nothing is fetched while in Offline mode.
 */
@HiltViewModel
class HomeShelvesViewModel @Inject constructor(
    private val newFromArtistsRepository: NewFromArtistsRepository,
    private val streamingPreference: StreamingPreference,
) : ViewModel() {

    private val newFromArtists = MutableStateFlow<List<AlbumSummary>>(emptyList())

    val state: StateFlow<HomeShelvesState> = combine(streamingPreference.enabled, newFromArtists) { online, releases ->
        if (online) HomeShelvesState(newFromArtists = releases) else HomeShelvesState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeShelvesState())

    private var building: Job? = null

    init {
        // Online again (or online at launch): fill the shelves.
        viewModelScope.launch {
            streamingPreference.enabled.distinctUntilChanged().collect { online -> if (online) refresh() }
        }
    }

    /** Home is back in view: a new day builds the shelves again; otherwise this only reads the saved ones. */
    fun onResume() {
        viewModelScope.launch { if (streamingPreference.enabled.first()) refresh() }
    }

    private fun refresh() {
        if (building?.isActive == true) return
        building = viewModelScope.launch {
            try {
                // Yesterday's row paints at once; today's replaces it when built.
                if (newFromArtists.value.isEmpty()) newFromArtists.value = newFromArtistsRepository.cached()
                newFromArtists.value = newFromArtistsRepository.shelf()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // A shelf is a nicety: whatever went wrong, Home keeps what it shows.
                Log.w(TAG, "New from your artists failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "HomeShelves"
    }
}
