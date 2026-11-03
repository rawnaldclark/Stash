package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.data.ytmusic.model.MoodTile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What Search shows before anything is typed: YouTube Music's [moods] and [genres] tiles, each with the [covers]
 * saved or read for it (by params). [online] is null until the Online/Offline switch is read; Offline there are no
 * tiles. [switchedOff] is Settings > Appearance > Moods & genres in Search turned off: no tiles either, as Offline.
 * [loaded] turns true once Search knows whether it has tiles: at once when some were saved, else when today's
 * read is over (answered, failed, or no network to try), so a fresh install waits for its first tiles rather than
 * deciding there are none.
 */
data class MoodBrowseState(
    val online: Boolean? = null,
    val switchedOff: Boolean = false,
    val loaded: Boolean = false,
    val moods: List<MoodTile> = emptyList(),
    val genres: List<MoodTile> = emptyList(),
    val covers: Map<String, List<String>> = emptyMap(),
) {
    /** The grid has something to show: Online with the switch on, with tiles in either section. */
    val hasTiles: Boolean get() = online == true && !switchedOff && (moods.isNotEmpty() || genres.isNotEmpty())

    /**
     * Whether Search shows the grid before anything is typed: true with tiles, false Offline, with the switch off, or
     * once it's known there are none (a fresh install without network, YouTube not answering), null while that isn't
     * known yet. False is Search as it was before the grid: the recent searches, the field focused on the first open.
     */
    val showsGrid: Boolean? get() = when {
        online == false || switchedOff -> false
        hasTiles -> true
        online == true && loaded -> false
        else -> null
    }
}

/**
 * Search's "Moods & moments" and "Genres" ([MoodsRepository]): the saved tiles painted at once, then today's (a new
 * read once a day), and each tile's two covers. Covers are asked for only for the tiles on screen ([onTilesVisible],
 * Mochy Music's per-tile request), and only once a visit: a tile whose read failed or was held by Data saver is asked
 * again on the next return to Search ([onResume]), never in a loop. The repository keeps them a week, reads two pages
 * at a time and leaves a metered network alone while Data saver is on.
 *
 * Follows the Online/Offline switch like Home's shelves: Offline there are no tiles, nothing is read, and what was
 * being read stops at once. With no network the saved tiles show and no read is tried. The Settings switch
 * ([HomeSectionsPreference.searchBrowseOn]) turned off does the same as Offline: no tiles, and nothing is read for
 * them, not the day's grids, not a cover, not even the saved ones.
 */
@HiltViewModel
class MoodBrowseViewModel @Inject constructor(
    private val moods: MoodsRepository,
    streamingPreference: StreamingPreference,
    private val connectivity: ConnectivityMonitor,
    homeSections: HomeSectionsPreference,
) : ViewModel() {

    private val _state = MutableStateFlow(MoodBrowseState())
    val state: StateFlow<MoodBrowseState> = _state.asStateFlow()

    private var loading: Job? = null
    private val coverReads = HashMap<String, Job>()

    /** Tiles answered this visit, and tiles whose read failed or was held: neither is asked again until [onResume]. */
    private val answered = HashSet<String>()
    private val held = HashSet<String>()

    /** The tiles on screen now, by params: a read whose turn comes after its tile left the screen isn't made. */
    @Volatile private var onScreen: Set<String> = emptySet()
    private var lastVisible: List<MoodTile> = emptyList()

    init {
        viewModelScope.launch {
            combine(streamingPreference.enabled, homeSections.searchBrowseOn, ::Pair)
                .distinctUntilChanged()
                .collect { (online, on) ->
                    if (online && on) {
                        _state.update { it.copy(online = true, switchedOff = false) }
                        refresh()
                        onTilesVisible(lastVisible)
                    } else {
                        stop()
                        _state.value = MoodBrowseState(online = online, switchedOff = !on)
                    }
                }
        }
    }

    /** Online with the switch on: the only time tiles show or anything is read for them. */
    private val active: Boolean get() = _state.value.online == true && !_state.value.switchedOff

    /**
     * Search is back in view: today's tiles if a read is due, and the tiles on screen asked for their covers again
     * (the saved ones answer without a request until they're a week old).
     */
    fun onResume() {
        if (!active) return
        answered.clear()
        held.clear()
        refresh()
        onTilesVisible(lastVisible)
    }

    /** The tiles on screen changed (Search's grid reports them once a scroll settles): read the covers they lack. */
    fun onTilesVisible(tiles: List<MoodTile>) {
        lastVisible = tiles
        onScreen = tiles.mapTo(HashSet()) { it.params }
        if (!active) return
        for (tile in tiles) {
            val params = tile.params
            if (params in answered || params in held || coverReads[params]?.isActive == true) continue
            coverReads[params] = viewModelScope.launch {
                val urls = try {
                    // No network: nothing is spent, and the tile is asked again on the next visit
                    if (connectivity.isConnected()) moods.covers(params, tile.title) { params in onScreen } else null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "covers of '${tile.title}' failed: ${e.message}")
                    null
                }
                when {
                    urls != null -> {
                        answered += params
                        if (_state.value.covers[params] != urls) _state.update { it.copy(covers = it.covers + (params to urls)) }
                    }
                    // Still on screen, so it wasn't scrolled away: no answer, or Data saver. Next visit.
                    params in onScreen -> held += params
                }
            }
        }
    }

    private fun refresh() {
        if (loading?.isActive == true) return
        loading = viewModelScope.launch {
            try {
                if (!_state.value.loaded) {
                    // Yesterday's tiles and every saved cover paint at once; today's replace them when read. With
                    // none saved, Search doesn't know yet whether it has a grid: today's read tells it.
                    val saved = moods.cached()
                    val covers = moods.savedCovers()
                    _state.update {
                        it.copy(loaded = !saved.isEmpty(), moods = saved.moods, genres = saved.genres, covers = covers)
                    }
                }
                if (!connectivity.isConnected()) {
                    _state.update { it.copy(loaded = true) }
                    return@launch
                }
                val grids = moods.grids()
                _state.update { it.copy(loaded = true, moods = grids.moods, genres = grids.genres) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The grid is a nicety: whatever went wrong, Search keeps what it shows
                Log.w(TAG, "moods and genres failed: ${e.message}")
                _state.update { it.copy(loaded = true) }
            }
        }
    }

    /** Offline or switched off: everything being read stops, and the next Online with the switch on starts afresh. */
    private fun stop() {
        loading?.cancel()
        loading = null
        coverReads.values.forEach { it.cancel() }
        coverReads.clear()
        answered.clear()
        held.clear()
    }

    private companion object {
        const val TAG = "MoodBrowse"
    }
}
