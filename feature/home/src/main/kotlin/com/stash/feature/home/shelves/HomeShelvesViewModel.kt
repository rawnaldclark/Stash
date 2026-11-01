package com.stash.feature.home.shelves

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.home.NewFromArtistsRepository
import com.stash.core.data.prefs.HomeSection
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.MoodTile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What Home's own streaming shelves show. A list is empty when its section is hidden, and every list is empty in
 * Offline mode (the app's Download mode plays only what's on the phone, and these shelves are streaming content),
 * so Home renders nothing for them there.
 */
data class HomeShelvesState(
    /** "New from your artists": album and single cards, newest first. */
    val newFromArtists: List<AlbumSummary> = emptyList(),
    /** "Moods & moments": YouTube Music's mood tiles, each opening its page of playlists. */
    val moods: List<MoodTile> = emptyList(),
)

/**
 * Home's own shelves, apart from [com.stash.feature.home.HomeViewModel] (which carries the Discover hero, the synced
 * mixes and the Qobuz rows): "New from your artists" and "Moods & moments". Each is built by its repository at most
 * once a day and saved to a file.
 *
 * A shelf loads only while it's wanted: Streaming on and its section shown in Settings > Home layout, the rule the
 * Qobuz rows follow (HomeViewModel's discovery flow): a hidden section costs no request. It loads when it becomes
 * wanted (Home first shown, the section turned back on, Offline mode switched off) and on each return to Home (a file
 * read until a rebuild is due); a shelf no longer wanted stops loading at once. Each shelf loads on its own, so none
 * waits on another. With no network the saved shelf is shown and no build is tried, so a failed attempt (and the
 * wait after it) only comes from a real network.
 */
@HiltViewModel
class HomeShelvesViewModel @Inject constructor(
    newFromArtistsRepository: NewFromArtistsRepository,
    moodsRepository: MoodsRepository,
    streamingPreference: StreamingPreference,
    homeSectionsPreference: HomeSectionsPreference,
    private val connectivity: ConnectivityMonitor,
) : ViewModel() {

    private val newFromArtists = Shelf(HomeSection.NEW_FROM_ARTISTS, newFromArtistsRepository::cached, newFromArtistsRepository::shelf)
    private val moods = Shelf(HomeSection.MOODS, moodsRepository::cached, moodsRepository::tiles)
    private val shelves = listOf(newFromArtists, moods)

    /** The shelf sections Home shows now: none in Offline mode, else the visible ones. */
    private val wanted: StateFlow<Set<HomeSection>> =
        combine(streamingPreference.enabled, homeSectionsPreference.visibleSections) { online, visible ->
            if (online) shelves.mapTo(HashSet()) { it.section }.filterTo(HashSet()) { it in visible } else emptySet()
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val state: StateFlow<HomeShelvesState> = combine(wanted, newFromArtists.items, moods.items) { shown, releases, tiles ->
        HomeShelvesState(
            newFromArtists = if (HomeSection.NEW_FROM_ARTISTS in shown) releases else emptyList(),
            moods = if (HomeSection.MOODS in shown) tiles else emptyList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeShelvesState())

    init {
        viewModelScope.launch {
            var before = emptySet<HomeSection>()
            wanted.collect { now ->
                for (shelf in shelves) {
                    when {
                        // Shown again (or for the first time): fill it
                        shelf.section in now && shelf.section !in before -> shelf.refresh()
                        // Hidden, or Offline mode: stop what it's loading
                        shelf.section !in now -> shelf.stop()
                    }
                }
                before = now
            }
        }
    }

    /** Home is back in view: each wanted shelf is built again if due; otherwise this only reads the saved ones. */
    fun onResume() {
        val now = wanted.value
        shelves.filter { it.section in now }.forEach { it.refresh() }
    }

    /** One shelf: its saved list painted at once, then the repository's current one; one load at a time. */
    private inner class Shelf<T>(
        val section: HomeSection,
        private val cached: suspend () -> List<T>,
        private val current: suspend () -> List<T>,
    ) {
        val items = MutableStateFlow<List<T>>(emptyList())
        private var loading: Job? = null

        fun refresh() {
            if (loading?.isActive == true) return
            loading = viewModelScope.launch {
                try {
                    // Yesterday's shelf paints at once; today's replaces it when built.
                    if (items.value.isEmpty()) items.value = cached()
                    // No network: keep the saved shelf, and don't spend an attempt the shelf would then wait out
                    if (!connectivity.isConnected()) return@launch
                    items.value = current()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // A shelf is a nicety: whatever went wrong, Home keeps what it shows.
                    Log.w(TAG, "$section failed: ${e.message}")
                }
            }
        }

        fun stop() {
            loading?.cancel()
            loading = null
        }
    }

    private companion object {
        const val TAG = "HomeShelves"
    }
}
