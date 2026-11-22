package com.stash.feature.community.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.community.stationMessage
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationLive
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A station's page in the app (Sessions step 2): where a `stashfm.app/s/{id}` link lands, and where a followed
 * station that isn't on the air opens from Home. It shows the station and, while it's on the air, its session;
 * Follow here shows at once and rolls back with the reason when the Worker refuses. Tuning in is the Sessions
 * lists' own ([SessionsViewModel.tuneIn]), so the first tune-in's name line and its other rules are the same here.
 */
@HiltViewModel
class StationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stations: StationsRepository,
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState

        /** Missing, removed, hidden by votes, or Sessions (or Community) off: "This station isn't available". */
        data object Unavailable : UiState

        /** It couldn't be reached: Retry. */
        data object Failed : UiState

        /** [live] is its session while it's on the air. */
        data class Loaded(val station: Station, val live: StationLive?) : UiState
    }

    /** StationRoute(stationId). Type-safe navigation stores it under the property name. Station ids keep their case. */
    val stationId: String = savedStateHandle.get<String>("stationId").orEmpty()

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A follow is on its way: another tap waits. Main thread only. */
    private var following = false

    init { load() }

    fun load() {
        if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = stations.station(stationId)) {
                is CommunityResult.Ok -> UiState.Loaded(r.value.station, r.value.live)
                is CommunityResult.Rejected -> if (r.code in SAYS_UNAVAILABLE) UiState.Unavailable else UiState.Failed
                is CommunityResult.Failed -> UiState.Failed
            }
        }
    }

    /** Follow, or stop following: the button and the count change at once, then take the Worker's. */
    fun follow() {
        val loaded = _state.value as? UiState.Loaded ?: return
        val station = loaded.station
        if (station.mine || following) return
        following = true
        val on = !station.following
        patch { it.copy(following = on, followers = (it.followers + if (on) 1 else -1).coerceAtLeast(0)) }
        viewModelScope.launch {
            try {
                when (val r = stations.follow(station.id, on)) {
                    is CommunityResult.Ok -> patch { it.copy(following = r.value.following, followers = r.value.followers) }
                    else -> {
                        patch { it.copy(following = station.following, followers = station.followers) }
                        _message.value = stationMessage(r)
                    }
                }
            } finally {
                following = false
            }
        }
    }

    private fun patch(change: (Station) -> Station) = _state.update { s -> if (s is UiState.Loaded) s.copy(station = change(s.station)) else s }

    fun messageShown() { _message.value = null }

    private companion object {
        /** The Worker's "not there for you" answers, and the ones that mean Sessions isn't on at all. */
        val SAYS_UNAVAILABLE = setOf("gone", "not_found", "off", CommunityResult.SESSIONS_OFF)
    }
}
