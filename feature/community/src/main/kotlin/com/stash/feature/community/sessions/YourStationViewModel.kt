package com.stash.feature.community.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationEdit
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.community.stationMessage
import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.AutoLiveMode
import com.stash.core.model.community.AutoLiveSchedule
import com.stash.core.model.community.ScheduleWindow
import com.stash.core.model.community.StationSchedule
import com.stash.core.model.community.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Your station (Sessions step 2): this phone's own station, with its name and vibe line to change and Delete. Every
 * change shows only once the Worker has taken it: a refused one leaves the screen as it was and says why.
 */
@HiltViewModel
class YourStationViewModel @Inject constructor(
    private val stations: StationsRepository,
    private val controller: ListenTogetherController,
    private val sharePreference: SharePreference,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val station: Station? = null,
        /** Loaded, and this phone has no station (it was deleted elsewhere, or never made). */
        val none: Boolean = false,
        /** It couldn't load: Retry. */
        val failed: Boolean = false,
        /** A change is on its way: the buttons wait. */
        val saving: Boolean = false,
        /** "Delete this station?" is showing. */
        val confirmingDelete: Boolean = false,
        /** Deleted: the screen leaves. */
        val deleted: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** This phone's room: while it's on the air, Go live now reads "On the air" and opens the session. */
    val room: StateFlow<ListenTogetherState> get() = controller.state

    /** Requests play without asking, in every session this station opens. Kept on this phone. */
    val autoAccept: StateFlow<Boolean> = sharePreference.autoAccept.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Remembered for the next session, and told to the one on the air now, if there is one. */
    fun setAutoAccept(on: Boolean) {
        viewModelScope.launch { sharePreference.setAutoAccept(on) }
        val room = controller.state.value
        if (room is ListenTogetherState.InRoom && room.isHost && room.session != null) controller.send(Command.SetAutoAccept(on))
    }

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val r = stations.mine()
            _state.update {
                when (r) {
                    is CommunityResult.Ok -> it.copy(loading = false, station = r.value, none = r.value == null)
                    else -> it.copy(loading = false, failed = it.station == null, message = if (it.station != null) stationMessage(r) else it.message)
                }
            }
        }
    }

    /** A new name. Blank, or the name it has, sends nothing. */
    fun rename(name: String) {
        val wanted = name.trim()
        val station = _state.value.station ?: return
        if (wanted.isEmpty() || wanted == station.name) return
        change(station, StationEdit(name = wanted))
    }

    /** A new vibe line; blank clears it. */
    fun setVibe(vibe: String) {
        val wanted = vibe.trim()
        val station = _state.value.station ?: return
        if (wanted == station.vibe.orEmpty()) return
        change(station, StationEdit(vibe = wanted))
    }

    /** Off, On a schedule or Always. A schedule goes with its hours, so the Worker never holds a mode without them. */
    fun setAutoLive(mode: AutoLiveMode) {
        val station = _state.value.station ?: return
        if (AutoLiveMode.of(station.autoLive) == mode) return
        val schedule = if (mode == AutoLiveMode.SCHEDULE) station.schedule ?: StationSchedule(zone(), emptyList()) else null
        change(station, StationEdit(autoLive = mode.wire, schedule = schedule))
    }

    /** One more window of hours. The phone's time zone travels with them. Refused past [AutoLiveSchedule.MAX_WINDOWS], or malformed. */
    fun addHours(window: ScheduleWindow) {
        val station = _state.value.station ?: return
        val now = station.schedule?.windows.orEmpty()
        if (!AutoLiveSchedule.isValid(window) || now.size >= AutoLiveSchedule.MAX_WINDOWS || window in now) return
        val sorted = (now + window).sortedWith(compareBy({ it.day }, { it.start }))
        change(station, StationEdit(schedule = StationSchedule(zone(), sorted)))
    }

    fun removeHours(window: ScheduleWindow) {
        val station = _state.value.station ?: return
        val now = station.schedule?.windows.orEmpty()
        if (window !in now) return
        change(station, StationEdit(schedule = StationSchedule(zone(), now - window)))
    }

    /** Test seam: the phone's time zone. */
    internal var zone: () -> String = { java.time.ZoneId.systemDefault().id }

    private fun change(station: Station, edit: StationEdit) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val r = stations.update(station.id, edit)
            _state.update {
                if (r is CommunityResult.Ok) it.copy(saving = false, station = r.value) else it.copy(saving = false, message = stationMessage(r))
            }
        }
    }

    fun askDelete() = _state.update { it.copy(confirmingDelete = it.station != null) }

    fun cancelDelete() = _state.update { it.copy(confirmingDelete = false) }

    /** After the confirm: the station goes with its votes and follows, and a session on the air ends at once. */
    fun delete() {
        val station = _state.value.station ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, confirmingDelete = false) }
        viewModelScope.launch {
            val r = stations.delete(station.id)
            _state.update {
                if (r is CommunityResult.Ok) it.copy(saving = false, deleted = true) else it.copy(saving = false, message = stationMessage(r))
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
