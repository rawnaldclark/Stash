package com.stash.feature.listening

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningHistoryPreference
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.listening.ListeningRange
import com.stash.core.data.listening.ListeningStatsCalculator
import com.stash.core.data.mapper.toDomain
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.feature.listening.stats.ListeningInsights
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    /** The last 7 days, for the summary card. */
    val week: WeekSummary = WeekSummary.EMPTY,
    /** The #1 song of the last 30 days, for the Top songs door; null when there's none. */
    val topSongTitle: String? = null,
)

/** Home's summary card: the last 7 days (today and the 6 before), "Last 7 days" on screen. */
data class WeekSummary(
    val listenedMs: Long,
    val plays: Int,
    /** Some of [listenedMs] is estimated from song length: the card says "about". */
    val estimated: Boolean,
    /** Listening time per day, oldest first, today last (7 values). */
    val bars: List<Long>,
    /** Consecutive listening days ending today (or yesterday). */
    val streak: Int,
) {
    companion object {
        val EMPTY = WeekSummary(0, 0, false, List(7) { 0L }, 0)
    }
}

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
    computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        history: ListeningHistoryRepository,
        historyPreference: ListeningHistoryPreference,
        playerRepository: PlayerRepository,
        connectivityMonitor: ConnectivityMonitor,
    ) : this(history, historyPreference, playerRepository, connectivityMonitor, minuteClock(), Dispatchers.Default)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-off messages for a toast ("This song isn't downloaded…"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val today: Flow<Long> = clock.map { history.today() }.distinctUntilChanged()

    /**
     * Today's count, the last 7 days' numbers and the streak, from the week's totals and one
     * day-count read back to the first listen (as Your listening reads it, so both show the same
     * streak however long it is).
     */
    private val days: Flow<Pair<Int, WeekSummary>> = today.flatMapLatest { t ->
        val week = DayRange(t - 6, t)
        flow {
            // No listen yet: nothing before today to count (the section shows with the first one).
            val first = history.firstListenDay() ?: week.fromDay
            emitAll(combine(
                history.observeTotals(week),
                history.observeDayCounts(DayRange(minOf(first, week.fromDay), t)),
            ) { totals, counts ->
            val byDay = counts.associateBy { it.day }
            val summary = WeekSummary(
                listenedMs = totals.listenedMs,
                plays = totals.plays,
                estimated = totals.estimatedPlays > 0,
                bars = (week.fromDay..week.toDay).map { byDay[it]?.listenedMs ?: 0L },
                streak = ListeningStatsCalculator.currentStreak(counts, t)?.days ?: 0,
            )
            (byDay[t]?.plays ?: 0) to summary
            })
        }
    }.distinctUntilChanged()

    /** The last 30 days' #1 song, counted the way Top songs counts (one row per song). */
    private val topSong: Flow<String?> = today.flatMapLatest { t ->
        history.observeTopTracks(ListeningRange.Days30.toDayRange(t), ListeningInsights.SCAN_LIMIT)
    }.map { ListeningInsights.mergeSameSongs(it).firstOrNull()?.track?.title }.distinctUntilChanged()

    val state: StateFlow<YourListeningState?> = combine(
        history.observeRecentTracks(),
        days,
        historyPreference.paused,
        clock,
        topSong,
    ) { recent, (todayCount, week), paused, now, top ->
        if (recent.isEmpty()) {
            null
        } else {
            YourListeningState(
                // Library duplicates of one song show once, the newest (the rail is newest first).
                recent = recent.distinctBy { ListeningInsights.songKey(it.track.title, it.track.artist) }
                    .map { it.track.toDomain() },
                todayPlays = todayCount,
                lastPlayedAt = recent.first().lastPlayedAt,
                paused = paused,
                nowMs = now,
                week = week,
                topSongTitle = top,
            )
        }
    }.flowOn(computeDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
