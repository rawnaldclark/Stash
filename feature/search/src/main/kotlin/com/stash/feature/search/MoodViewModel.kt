package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.home.MoodsRepository
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.data.ytmusic.model.MoodShelf
import com.stash.data.ytmusic.model.TrackSummary
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
 * What a mood's or a genre's page shows: its name, then a spinner, YouTube's rows (songs, playlists, albums), or a
 * retry; Offline ([offline]), only the way back to Online, since everything on it streams.
 */
data class MoodUiState(
    val title: String,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val offline: Boolean = false,
    val shelves: List<MoodShelf> = emptyList(),
)

/**
 * A mood's or a genre's page ([MoodScreen]): reads the page of the tile it was opened from ([ARG_PARAMS], named
 * [ARG_TITLE] until the page's own header names it) through [MoodsRepository.page], once, and again on [retry].
 * Follows the Online/Offline switch like Search's grid: Offline it reads nothing and says so (it's reachable from the
 * back stack after the switch), and turning Online back on loads it.
 */
@HiltViewModel
class MoodViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val moods: MoodsRepository,
    streamingPreference: StreamingPreference,
    private val playerRepository: PlayerRepository,
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
                    // Back from Offline, or the first look: read the page unless it's on screen already
                    if (_state.value.shelves.isEmpty()) retry()
                }
            }
        }
    }

    /** The page didn't load: ask again. Nothing while Offline, where the page stays closed. */
    fun retry() {
        if (loading?.isActive == true || _state.value.offline) return
        _state.update { it.copy(loading = true, failed = false) }
        load()
    }

    /**
     * Plays a genre's Songs row from its song at [index], streamed (these are songs you mostly don't have), the row
     * as the queue: Home's "Because you like" rows play the same way. Now Playing names it after the page ("Rock").
     */
    fun playSongs(songs: List<TrackSummary>, index: Int) {
        val tracks = songs.map { s ->
            Track(
                id = s.videoId.hashCode().toLong(),
                title = s.title,
                artist = s.artist,
                album = s.album.orEmpty(),
                durationMs = (s.durationSeconds * 1000.0).toLong(),
                albumArtUrl = s.thumbnailUrl,
                youtubeId = s.videoId,
                source = MusicSource.YOUTUBE,
                isStreamable = true,
            )
        }
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            playerRepository.setQueue(tracks, index.coerceIn(0, tracks.lastIndex), PlaybackSource.Listening(_state.value.title))
        }
    }

    private fun load() {
        loading = viewModelScope.launch {
            try {
                val page = moods.page(params, title)
                _state.value = MoodUiState(title = page.title.ifBlank { title }, loading = false, shelves = page.shelves)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "page '$title' didn't load: ${e.message}")
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
