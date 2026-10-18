package com.stash.feature.listening

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningHistoryPreference
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.mapper.toDomain
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What Home's "Your listening" section shows. Null = nothing to show: still loading, or no listen
 * yet (no empty pills on day one; the section appears with the first listen).
 */
data class YourListeningState(
    /** "Recently played": the last songs heard, newest first, each once. */
    val recent: List<Track>,
    /** Listens today, for the History card ("12 plays today"). */
    val todayPlays: Int,
    /** When the newest listen started (epoch ms). */
    val lastPlayedAt: Long,
    /** History paused in Settings: the History card says so. */
    val paused: Boolean,
    /** The clock the ages were worked out at (epoch ms), so "2 h ago" moves on while Home is open. */
    val nowMs: Long,
)

/**
 * Feeds [YourListeningSection]: the recent songs, today's count and the pause state, refreshed on
 * every new or removed listen and once a minute for the ages (stopped while Home isn't shown).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YourListeningViewModel @VisibleForTesting internal constructor(
    private val history: ListeningHistoryRepository,
    historyPreference: ListeningHistoryPreference,
    private val playerRepository: PlayerRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val clock: Flow<Long>,
) : ViewModel() {

    @Inject
    constructor(
        history: ListeningHistoryRepository,
        historyPreference: ListeningHistoryPreference,
        playerRepository: PlayerRepository,
        connectivityMonitor: ConnectivityMonitor,
    ) : this(history, historyPreference, playerRepository, connectivityMonitor, minuteClock())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-off messages for a toast ("This song isn't downloaded…"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val todayPlays: Flow<Int> = clock
        .map { history.today() }
        .distinctUntilChanged()
        .flatMapLatest { today -> history.observeDayCounts(DayRange(today, today)) }
        .map { counts -> counts.sumOf { it.plays } }
        .distinctUntilChanged()

    val state: StateFlow<YourListeningState?> = combine(
        history.observeRecentTracks(),
        todayPlays,
        historyPreference.paused,
        clock,
    ) { recent, today, paused, now ->
        if (recent.isEmpty()) {
            null
        } else {
            YourListeningState(
                recent = recent.map { it.track.toDomain() },
                todayPlays = today,
                lastPlayedAt = recent.first().lastPlayedAt,
                paused = paused,
                nowMs = now,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Plays [track] from the "Recently played" rail, with the rail's other songs around it. */
    fun playRecent(track: Track) {
        val recent = state.value?.recent ?: return
        val index = recent.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: return
        viewModelScope.launch {
            when (val plan = planPlay(recent, index, connectivityMonitor.isConnected(), tapped = true)) {
                is PlayPlan.Play -> playerRepository.setQueue(plan.tracks, plan.startIndex, PlaybackSource.Listening(RECENT_LABEL))
                is PlayPlan.Blocked -> _messages.tryEmit(plan.message)
            }
        }
    }

    companion object {
        /** The "Playing from" line of a queue started from the rail. */
        const val RECENT_LABEL = "Recently played"

        /** The current time now and then once a minute, while collected. */
        private fun minuteClock(): Flow<Long> = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000)
            }
        }
    }
}
