package com.stash.feature.nowplaying.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationEdit
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.community.stationMessage
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.share.SharePreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Go live (Sessions step 2; UX spec 2026-11-20 §8): from Now Playing's action, the Sessions screen or Your station.
 * The first time, a sheet names the station and says what going live shares; its Go live makes the station, then
 * opens the session. After that it's one tap. The engine opens the session and says why in its own toast when the
 * server refuses ([ListenTogetherController.Command.GoLive]); this says why when the station itself can't be made or
 * changed.
 */
@HiltViewModel
class GoLiveViewModel @Inject constructor(
    private val stations: StationsRepository,
    homeSections: HomeSectionsPreference,
    private val share: SharePreference,
    private val controller: ListenTogetherController,
    private val player: PlayerRepository,
) : ViewModel() {
    sealed interface Step {
        data object Idle : Step

        /** Reading this phone's station. */
        data object Checking : Step

        /**
         * The sheet. [station] is null until its Go live has made one; after that (a go-live the server refused) the
         * sheet holds it, and Go live saves what changed instead of making a second. [hostName]: the name to go by,
         * prefilled with the display name.
         */
        data class Form(val station: Station? = null, val hostName: String = "", val sending: Boolean = false) : Step

        /** The session is opening. */
        data object Going : Step
    }

    /** A line about a room this phone is already in, which Go live can't start from. */
    enum class Note { END_PRIVATE_FIRST, LEAVE_FIRST }

    data class UiState(
        /** Community is on and the Worker says Sessions is: Go live is offered. */
        val available: Boolean = false,
        val step: Step = Step.Idle,
        val message: String? = null,
        val note: Note? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** This phone's room: on the air, the action reads "On the air · 12". */
    val room: StateFlow<ListenTogetherState> get() = controller.state

    /** What the tap that began this go-live runs once the session is open (opening the player on it). */
    private var onOpened: () -> Unit = {}

    init {
        viewModelScope.launch {
            combine(homeSections.communityOn, stations.sessionsOn) { community, sessions -> community to sessions }
                .collect { (community, sessions) ->
                    _state.update { it.copy(available = community && sessions == true) }
                    // Nothing has asked the Worker yet (Now Playing opened before Home's section loaded): ask once.
                    if (community && sessions == null) stations.ensureKnown()
                }
        }
    }

    /** The Go live tap. [opened] runs once this phone is on the air. */
    fun begin(opened: () -> Unit) {
        if (_state.value.step != Step.Idle) return
        val now = controller.state.value
        if (now is ListenTogetherState.InRoom) {
            when {
                now.session != null && now.isHost -> opened()
                now.session == null -> _state.update { it.copy(note = Note.END_PRIVATE_FIRST) }
                else -> _state.update { it.copy(note = Note.LEAVE_FIRST) }
            }
            return
        }
        onOpened = opened
        _state.update { it.copy(step = Step.Checking) }
        viewModelScope.launch {
            when (val mine = stations.mine()) {
                is CommunityResult.Ok -> {
                    val station = mine.value
                    if (station == null) {
                        _state.update { it.copy(step = Step.Form(hostName = share.displayName().orEmpty())) }
                    } else {
                        goLive(station, fromForm = false)
                    }
                }
                else -> _state.update { it.copy(step = Step.Idle, message = stationMessage(mine)) }
            }
        }
    }

    /**
     * The sheet's Go live. Makes the station, or saves what changed on the one the sheet already holds, then goes live.
     * A refusal says why and leaves the sheet as it was.
     */
    fun submit(name: String, hostName: String, vibe: String) {
        val form = _state.value.step as? Step.Form ?: return
        val wantedName = name.trim()
        val wantedHost = hostName.trim()
        val wantedVibe = vibe.trim()
        if (form.sending || wantedName.isEmpty()) return
        _state.update { it.copy(step = form.copy(sending = true, hostName = wantedHost)) }
        viewModelScope.launch {
            if (wantedHost.isNotEmpty() && wantedHost != share.displayName()) share.setDisplayName(wantedHost)
            val had = form.station
            val station: Station? = if (had == null) {
                val edit = StationEdit(name = wantedName, hostName = wantedHost.ifEmpty { null }, vibe = wantedVibe.ifEmpty { null })
                when (val made = stations.create(edit)) {
                    is CommunityResult.Ok ->
                        Station(id = made.value, name = wantedName, hostName = edit.hostName, vibe = edit.vibe, mine = true, heard = true)
                    else -> refused(form, made)
                }
            } else {
                val edit = StationEdit(
                    name = wantedName.takeIf { it != had.name },
                    hostName = wantedHost.takeIf { it != had.hostName.orEmpty() },
                    vibe = wantedVibe.takeIf { it != had.vibe.orEmpty() },
                )
                if (edit == StationEdit()) had else when (val saved = stations.update(had.id, edit)) {
                    is CommunityResult.Ok -> saved.value
                    else -> refused(form, saved)
                }
            }
            if (station != null) goLive(station, fromForm = true)
        }
    }

    private fun refused(form: Step.Form, result: CommunityResult<*>): Station? {
        _state.update { it.copy(step = form.copy(sending = false), message = stationMessage(result)) }
        return null
    }

    /**
     * Opens the session and waits for the room, as the Join screen waits: on the air, or back to Idle after
     * Connecting (the engine has said why). From the sheet, a refusal comes back to the sheet, which now holds
     * [station].
     */
    private suspend fun goLive(station: Station, fromForm: Boolean) {
        _state.update { it.copy(step = Step.Going) }
        // A cold start only shows the last queue; load it, so the session starts from that song and not from nothing
        // (as starting a private room does).
        player.loadRestoredQueue()
        controller.send(ListenTogetherController.Command.GoLive(station.id, station.hostName))
        var connecting = false
        val outcome = withTimeoutOrNull(GO_LIVE_TIMEOUT_MS) {
            controller.state.first { s ->
                if (s is ListenTogetherState.Connecting) connecting = true
                (s is ListenTogetherState.InRoom && s.session != null && s.isHost) || (s is ListenTogetherState.Idle && connecting)
            }
        }
        if (outcome is ListenTogetherState.InRoom) {
            _state.update { it.copy(step = Step.Idle) }
            onOpened()
        } else {
            _state.update { it.copy(step = if (fromForm) Step.Form(station = station, hostName = station.hostName.orEmpty()) else Step.Idle) }
        }
    }

    /** Not now, or the sheet swiped away. A go-live already on its way carries on. */
    fun dismiss() = _state.update { if (it.step is Step.Form) it.copy(step = Step.Idle) else it }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun noteShown() = _state.update { it.copy(note = null) }

    private companion object {
        const val GO_LIVE_TIMEOUT_MS = 20_000L
    }
}
