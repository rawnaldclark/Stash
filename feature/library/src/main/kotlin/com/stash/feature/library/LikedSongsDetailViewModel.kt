package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.ui.util.withSearchFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LikedSongsDetailUiState(
    val title: String = "Liked Songs",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val source: MusicSource? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class LikedSongsDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val sourceFilter: MusicSource? =
        savedStateHandle.get<String>("source")?.let { MusicSource.valueOf(it) }

    private val _searchQuery = MutableStateFlow("")
    private val _showSearch = MutableStateFlow(false)

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun clearSearch() { _searchQuery.value = "" }
    fun toggleSearch() {
        _showSearch.value = !_showSearch.value
        if (!_showSearch.value) _searchQuery.value = ""
    }

    private val tracksFlow = musicRepository.getPlaylistsByType(PlaylistType.LIKED_SONGS)
        .map { playlists ->
            if (sourceFilter != null) playlists.filter { it.source == sourceFilter }
            else playlists
        }
        .flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(playlists.map { musicRepository.getTracksByPlaylist(it.id) }) { arrays ->
                    arrays.flatMap { it.toList() }.distinctBy { it.id }
                }
            }
        }

    val uiState: StateFlow<LikedSongsDetailUiState> = combine(
        tracksFlow.withSearchFilter(_searchQuery),
        playerRepository.playerState,
        _searchQuery,
        _showSearch,
    ) { tracks, playerState, query, showSearch ->
        val title = when (sourceFilter) {
            MusicSource.SPOTIFY -> "Liked Songs \u2022 Spotify"
            MusicSource.YOUTUBE -> "Liked Songs \u2022 YouTube"
            else -> "Liked Songs"
        }
        LikedSongsDetailUiState(
            title = title,
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
            source = sourceFilter,
            searchQuery = query,
            showSearch = showSearch,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LikedSongsDetailUiState(),
    )

    fun playTrack(trackId: Long) {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            val index = downloaded.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
            playerRepository.setQueue(downloaded, index)
        }
    }

    fun shuffleAll() {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            playerRepository.setQueue(downloaded, downloaded.indices.random())
        }
    }

    fun playNext(track: Track) {
        viewModelScope.launch { playerRepository.addNext(track) }
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch { playerRepository.addToQueue(track) }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch { musicRepository.deleteTrack(track) }
    }

    val userPlaylists = musicRepository.getUserCreatedPlaylists()

    fun saveTrackToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch { musicRepository.addTrackToPlaylist(trackId, playlistId) }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val id = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, id)
        }
    }
}
