package com.stash.feature.nowplaying.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.community.stationMessage
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The room's station (Sessions step 2): its name, vibe line, followers and this phone's follow, read from the
 * Worker when the phone enters a session and again after each Follow. The room itself (the song, the crowd, the
 * reactions) comes from the engine's [ListenTogetherState]; this adds what only the station's page knows.
 */
@HiltViewModel
class SessionRoomViewModel @Inject constructor(
    private val stations: StationsRepository,
    controller: ListenTogetherController,
) : ViewModel() {
    data class UiState(
        /** The station whose session this is; null until it's read (the room shows without it meanwhile). */
        val station: Station? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** A follow is on its way: another tap waits. Main thread only. */
    private var following = false

    init {
        viewModelScope.launch {
            controller.state
                .map { (it as? ListenTogetherState.InRoom)?.session?.station }
                .distinctUntilChanged()
                .collectLatest { id ->
                    _state.update { it.copy(station = null) }
                    if (id != null) read(id)
                }
        }
    }

    private suspend fun read(id: String) {
        val page = stations.station(id)
        if (page is CommunityResult.Ok) _state.update { it.copy(station = page.value.station) }
    }

    /**
     * Follow, or stop following: the button and the count change at once, take the Worker's count when it answers,
     * then the station is read again. A refusal puts both back and says why. Your own station has no Follow.
     */
    fun follow() {
        val station = _state.value.station ?: return
        if (station.mine || following) return
        following = true
        val on = !station.following
        _state.update { it.copy(station = station.copy(following = on, followers = (station.followers + if (on) 1 else -1).coerceAtLeast(0))) }
        viewModelScope.launch {
            try {
                when (val r = stations.follow(station.id, on)) {
                    is CommunityResult.Ok -> {
                        patch(station.id) { it.copy(following = r.value.following, followers = r.value.followers) }
                        read(station.id)
                    }
                    else -> {
                        patch(station.id) { it.copy(following = station.following, followers = station.followers) }
                        _state.update { it.copy(message = stationMessage(r)) }
                    }
                }
            } finally {
                following = false
            }
        }
    }

    /** Only while the room is still that station's: a slow answer mustn't write over the next session's station. */
    private fun patch(id: String, change: (Station) -> Station) =
        _state.update { s -> if (s.station?.id == id) s.copy(station = change(s.station)) else s }

    fun messageShown() = _state.update { it.copy(message = null) }
}
