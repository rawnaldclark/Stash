package com.stash.feature.community.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.community.communityMessage
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sessions' lists (Sessions step 2): Live now and the stations this phone follows, for Home's Community section and
 * the Sessions screen, and tuning in to one. Nothing loads, and [UiState.on] stays false, while Community is off or
 * until the Worker has said Sessions is on: the first list read is also how the phone learns that
 * ([StationsRepository.sessionsOn]). A Worker that says it's off isn't asked again in this run of the app.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val stations: StationsRepository,
    homeSections: HomeSectionsPreference,
    private val controller: ListenTogetherController,
    private val share: SharePreference,
) : ViewModel() {
    /** A line this screen says in its own words (the engine's refusals arrive as its own toasts). */
    enum class Note {
        /** A card was tapped while this phone is on the air: tuning in would end its session for everyone. */
        END_YOURS_FIRST,
    }

    data class UiState(
        /** Community is on and the Worker has said Sessions is. Until then nothing about Sessions shows. */
        val on: Boolean = false,
        /** Community is off, or the Worker has said Sessions is: the Sessions screen says it isn't available. */
        val off: Boolean = false,
        /** Live now, in the Worker's order (best first); null until it loads. */
        val live: List<LiveSession>? = null,
        /** The stations this phone follows, as the Worker sends them: on the air first, then the soonest on. */
        val following: List<Station> = emptyList(),
        /** This phone's own station (the Sessions screen only), or null without one. */
        val mine: Station? = null,
        /** Nothing loaded and the last try failed (also before the Worker could say whether Sessions is on). */
        val failed: Boolean = false,
        /** A pull-to-refresh is loading. */
        val refreshing: Boolean = false,
        val message: String? = null,
        val note: Note? = null,
        /** The station being tuned in to: its card shows progress, and other taps wait. */
        val tuningIn: String? = null,
        /**
         * Before the first tune-in ever: "Everyone in a session sees your name", with the name as it stands (blank
         * when none is set). Null when it isn't being asked.
         */
        val naming: String? = null,
    )

    private val communityOn = homeSections.communityOn
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loading: Job? = null
    private var loadedAt = 0L

    /** The Sessions screen: every live session ([ALL_LIVE]) and this phone's own station too. */
    private var all = false

    /** Test seam for the reload clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        // Community switched off, or the Worker's switch: everything shown goes.
        viewModelScope.launch {
            combine(communityOn, stations.sessionsOn) { community, sessions -> community to sessions }
                .collect { (community, sessions) ->
                    if (community && sessions == true) return@collect
                    val off = !community || sessions == false
                    // Only a change clears the screen: this first report can land after the first load has already
                    // said "couldn't tell" (failed), which must stay.
                    _state.update { if (it.on || it.off != off) UiState(off = off) else it }
                }
        }
        // A follow, or a station made, changed or deleted (the room, a station's page, Your station) reloads the lists.
        viewModelScope.launch { stations.revision.drop(1).collect { load() } }
    }

    /**
     * Each time the lists come on screen (Home resuming, the Sessions screen opening with [all]). It reloads unless they
     * loaded in the last 30 seconds, as Home's posts do. A clock set back counts as stale.
     */
    fun onShown(all: Boolean = false) {
        this.all = this.all || all
        if ((clock() - loadedAt) !in 0..RELOAD_AFTER_MS) load()
    }

    /** Pull-to-refresh: the lists stay on screen until the new ones arrive, and a failure says so. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(pulled = true)
    }

    private fun load(pulled: Boolean = false) {
        loading?.cancel()
        loading = viewModelScope.launch {
            if (!communityOn.first() || stations.sessionsOn.value == false) {
                _state.update { it.copy(refreshing = false) }
                return@launch
            }
            // When it was asked, not answered, so a change made during a slow load counts as newer.
            val asked = clock()
            // Live now first: its answer is also what says whether Sessions is on at all.
            val live = stations.live(if (all) ALL_LIVE else HOME_LIVE)
            when (stations.sessionsOn.value) {
                true -> Unit
                // Off: the collector above has said so, and this Worker isn't asked again.
                false -> return@launch
                // It couldn't say (offline): nothing shows on Home, the Sessions screen offers a retry, and the next
                // visit asks again.
                null -> {
                    _state.update { it.copy(failed = true, refreshing = false) }
                    return@launch
                }
            }
            val (following, mine) = coroutineScope {
                val f = async { stations.following() }
                val m = async { if (all) stations.mine() else null }
                f.await() to m.await()
            }
            if (live is CommunityResult.Ok) loadedAt = asked
            _state.update { s ->
                s.copy(
                    on = true,
                    live = (live as? CommunityResult.Ok)?.value ?: s.live,
                    following = (following as? CommunityResult.Ok)?.value ?: s.following,
                    mine = if (mine is CommunityResult.Ok) mine.value else s.mine,
                    failed = live !is CommunityResult.Ok && s.live == null,
                    refreshing = false,
                    // A quiet reload (Home coming back) that fails over a list on screen says nothing; a pulled one does.
                    message = if (pulled && live !is CommunityResult.Ok) communityMessage(live) else s.message,
                )
            }
        }
    }

    /** A Live now card: joins that session, then [onOpened] opens the player on the room. */
    fun tuneIn(session: LiveSession, onOpened: () -> Unit) = tuneIn(session.room, session.station.id, session.full, onOpened)

    /** One of Your stations: only while it's on the air. */
    fun tuneIn(station: Station, onOpened: () -> Unit) {
        val live = station.live ?: return
        tuneIn(live.room, station.id, live.full, onOpened)
    }

    /**
     * No preview and no confirm (UX §3): the tap is the join. [onOpened] runs once the room has let this phone in; a
     * session that refuses (ended, full by now, an app too old) says why in the engine's own toast, and the lists reload.
     */
    private fun tuneIn(room: String, stationId: String, full: Boolean, onOpened: () -> Unit) {
        if (full || _state.value.tuningIn != null || _state.value.naming != null) return
        val now = controller.state.value
        if (now is ListenTogetherState.InRoom) {
            if (now.code == room) return onOpened()
            // Tuning in leaves the room this phone is in, and a session's host leaving ends it for everyone listening.
            if (now.session != null && now.isHost) {
                _state.update { it.copy(note = Note.END_YOURS_FIRST) }
                return
            }
        }
        // Set before anything suspends, so a second tap finds it.
        _state.update { it.copy(tuningIn = stationId) }
        viewModelScope.launch {
            // The first tune-in ever says who sees your name first, once (UX: one line, then Tune in).
            if (!share.tuneInNameSeen()) {
                afterNaming = { join(room, stationId, onOpened) }
                _state.update { it.copy(tuningIn = null, naming = share.displayName().orEmpty()) }
            } else {
                join(room, stationId, onOpened)
            }
        }
    }

    /** The join waiting on the first tune-in's name line. Main thread only. */
    private var afterNaming: (() -> Unit)? = null

    /** The name line's Tune in: [name] is kept as the display name if it changed, the line never shows again, and the join goes. */
    fun confirmName(name: String) {
        val go = afterNaming ?: return
        afterNaming = null
        viewModelScope.launch {
            val wanted = name.trim()
            if (wanted != share.displayName().orEmpty()) share.setDisplayName(wanted)
            share.setTuneInNameSeen()
            _state.update { it.copy(naming = null) }
            go()
        }
    }

    /** Backed out of the name line: nothing is joined, and the next tune-in asks again. */
    fun cancelName() {
        afterNaming = null
        _state.update { it.copy(naming = null) }
    }

    private fun join(room: String, stationId: String, onOpened: () -> Unit) {
        _state.update { it.copy(tuningIn = stationId) }
        viewModelScope.launch {
            controller.send(ListenTogetherController.Command.TuneIn(room, stationId))
            // As the Join screen waits: in, or back to Idle after Connecting (an Idle before that is the old room closing).
            var connecting = false
            val outcome = withTimeoutOrNull(TUNE_IN_TIMEOUT_MS) {
                controller.state.first { s ->
                    if (s is ListenTogetherState.Connecting) connecting = true
                    (s is ListenTogetherState.InRoom && s.code == room) || (s is ListenTogetherState.Idle && connecting)
                }
            }
            _state.update { it.copy(tuningIn = null) }
            if (outcome is ListenTogetherState.InRoom) onOpened() else load()
        }
    }

    /** This phone's room, for the Sessions screen: while it's on the air, Go live reads "On the air" and opens the booth. */
    val room: StateFlow<ListenTogetherState> get() = controller.state

    /** The Sessions screen's Retry. */
    fun retry() = load()

    fun messageShown() = _state.update { it.copy(message = null) }

    fun noteShown() = _state.update { it.copy(note = null) }

    internal companion object {
        /** Home's Live now: the Worker's own default. */
        const val HOME_LIVE = 10

        /** The Sessions screen: as many as the Worker gives. */
        const val ALL_LIVE = 50
        const val RELOAD_AFTER_MS = 30_000L
        const val TUNE_IN_TIMEOUT_MS = 20_000L
    }
}
