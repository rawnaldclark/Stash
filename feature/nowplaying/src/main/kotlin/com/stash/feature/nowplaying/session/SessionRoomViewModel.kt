package com.stash.feature.nowplaying.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.community.stationMessage
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.Station
import com.stash.core.ui.components.VotePillUi
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
 * The room's station (Sessions step 2): its name, vibe line, followers, this phone's follow and vote, read from the
 * Worker when the phone enters a session and again after each Follow. The room itself (the song, the crowd, the
 * reactions) comes from the engine's [ListenTogetherState]; this adds what only the station's page knows.
 *
 * It also owns leaving: [leave] leaves at once, and after 2 minutes or more in a session it keeps the station as
 * [UiState.parting] for the small sheet that asks how it was (UX spec 2026-11-20 §4.6). One instance serves the room,
 * the mini player's close and that sheet (they share the activity's ViewModel store).
 */
@HiltViewModel
class SessionRoomViewModel @Inject constructor(
    private val stations: StationsRepository,
    private val controller: ListenTogetherController,
) : ViewModel() {
    /** Why a vote didn't go, in place of the usual message. */
    enum class Note {
        /** "Listen 2 minutes to vote": a dimmed arrow was tapped, or the Worker has no listen on record. */
        LISTEN_FIRST,

        /** The listen couldn't reach the Worker yet, so the vote wasn't sent. */
        TRY_AGAIN,
    }

    data class UiState(
        /** The station whose session this is; null until it's read (the room shows without it meanwhile). */
        val station: Station? = null,
        /** The station just left, while the sheet that asks about it is up. */
        val parting: Station? = null,
        val message: String? = null,
        val note: Note? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** A follow, or a vote, is on its way: another tap waits. Main thread only. */
    private var following = false
    private var voting = false

    /** The stations heard on this phone (2 minutes in their sessions), before the Worker knows. */
    private var heardHere: Set<String> = emptySet()

    /** When this phone entered the session it's in, and the room it last asked about on leaving. */
    private var enteredAt = 0L
    private var askedAbout: String? = null

    /** Test seam for the clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // Above init: its collectors may run at once.
    private val _heardVersion = MutableStateFlow(0)

    /** Changes when this phone's heard marks do: a screen that draws [votePill] reads it, so the pill follows them. */
    val heardVersion: StateFlow<Int> = _heardVersion.asStateFlow()

    init {
        viewModelScope.launch {
            controller.state
                .map { (it as? ListenTogetherState.InRoom)?.takeIf { r -> r.session != null }?.let { r -> r.code to r.session!!.station } }
                .distinctUntilChanged()
                .collectLatest { entered ->
                    _state.update { it.copy(station = null) }
                    if (entered != null) {
                        enteredAt = clock()
                        val page = stations.station(entered.second)
                        if (page is CommunityResult.Ok) _state.update { it.copy(station = page.value.station) }
                    }
                }
        }
        // A station heard while its room is on screen wakes its pill there and then.
        viewModelScope.launch {
            stations.heardIds.collect { ids ->
                heardHere = ids
                _heardVersion.value++
            }
        }
    }

    /** The station the vote and Follow act on: the one just left while its sheet is up, else the room's. */
    private fun target(): Station? = _state.value.parting ?: _state.value.station

    private fun heard(station: Station): Boolean = station.mine || station.heard || station.id in heardHere

    /**
     * The station's vote pill: its score, this phone's vote, and until the station is heard (2 minutes in its
     * sessions) the arrows that would cast a vote dimmed. Your own station's takes no taps. Null with no station.
     */
    fun votePill(): VotePillUi? {
        val s = target() ?: return null
        val listenFirst = !heard(s)
        return VotePillUi(
            score = s.score,
            upLit = s.myVote == 1,
            downLit = s.myVote == -1,
            enabled = !s.mine,
            listenFirst = listenFirst,
            upDimmed = listenFirst && s.myVote != 1,
            downDimmed = listenFirst && s.myVote != -1,
        )
    }

    /**
     * A tap of ▲ (1) or ▼ (-1): your vote again takes it back. It shows at once and takes the Worker's counts when
     * they come; a refusal flips it back. Casting one needs the station heard: until then the tap only says so.
     */
    fun vote(tapped: Int) {
        val station = target() ?: return
        if (station.mine || voting) return
        val value = if (station.myVote == tapped) 0 else tapped
        if (value != 0 && !heard(station)) {
            _state.update { it.copy(note = Note.LISTEN_FIRST) }
            return
        }
        voting = true
        patch(station.id) { it.withVote(value) }
        viewModelScope.launch {
            try {
                when (val r = stations.vote(station.id, value)) {
                    is CommunityResult.Ok -> patch(station.id) { it.copy(up = r.value.up, down = r.value.down, myVote = r.value.myVote) }
                    else -> {
                        val code = (r as? CommunityResult.Rejected)?.code
                        // The Worker has no listen on record: the pill dims again. Or the listen couldn't reach it yet.
                        patch(station.id) { now ->
                            now.copy(up = station.up, down = station.down, myVote = station.myVote).let {
                                if (code == CommunityRepository.LISTEN_FIRST) it.copy(heard = false) else it
                            }
                        }
                        _state.update {
                            when (code) {
                                CommunityRepository.LISTEN_FIRST -> it.copy(note = Note.LISTEN_FIRST)
                                CommunityRepository.VOTE_NOT_NOW -> it.copy(note = Note.TRY_AGAIN)
                                else -> it.copy(message = stationMessage(r))
                            }
                        }
                    }
                }
            } finally {
                voting = false
            }
        }
    }

    /**
     * Follow, or stop following: the button and the count change at once, take the Worker's count when it answers,
     * then (still in its room) the station is read again. A refusal puts both back and says why. Your own station
     * has no Follow.
     */
    fun follow() {
        val station = target() ?: return
        if (station.mine || following) return
        following = true
        val on = !station.following
        patch(station.id) { it.copy(following = on, followers = (it.followers + if (on) 1 else -1).coerceAtLeast(0)) }
        viewModelScope.launch {
            try {
                when (val r = stations.follow(station.id, on)) {
                    is CommunityResult.Ok -> {
                        patch(station.id) { it.copy(following = r.value.following, followers = r.value.followers) }
                        if (_state.value.station?.id == station.id) {
                            val page = stations.station(station.id)
                            if (page is CommunityResult.Ok) patch(station.id) { page.value.station }
                        }
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

    /**
     * Leave (the room's menu, the mini player's close). It leaves at once. A listener who was in for 2 minutes or
     * more is then asked how the station was, once for that session; a host's Leave ends the session and asks nothing.
     */
    fun leave() {
        val room = controller.state.value as? ListenTogetherState.InRoom
        val station = _state.value.station
        if (room != null && room.session != null && !room.isHost && station != null && !station.mine &&
            clock() - enteredAt >= ASK_AFTER_MS && askedAbout != room.code
        ) {
            askedAbout = room.code
            _state.update { it.copy(parting = station) }
        }
        controller.send(ListenTogetherController.Command.Leave)
    }

    /** The sheet was closed, or skipped. */
    fun partingDone() = _state.update { it.copy(parting = null) }

    /** Wherever that station is held (the room's, the sheet's), and only there: a slow answer never writes over another. */
    private fun patch(id: String, change: (Station) -> Station) = _state.update { s ->
        s.copy(
            station = s.station?.let { if (it.id == id) change(it) else it },
            parting = s.parting?.let { if (it.id == id) change(it) else it },
        )
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun noteShown() = _state.update { it.copy(note = null) }

    private companion object {
        /** In a session this long, leaving asks about the station (the vote needs the same 2 minutes). */
        const val ASK_AFTER_MS = 120_000L
    }
}

/** [this] as if [value] were now this phone's vote: the change shown before the Worker answers. */
internal fun Station.withVote(value: Int): Station = copy(
    up = up - (if (myVote == 1) 1 else 0) + (if (value == 1) 1 else 0),
    down = down - (if (myVote == -1) 1 else 0) + (if (value == -1) 1 else 0),
    myVote = value,
)
