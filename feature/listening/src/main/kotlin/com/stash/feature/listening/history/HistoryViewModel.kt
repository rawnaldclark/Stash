package com.stash.feature.listening.history

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.HistoryRow
import com.stash.core.data.db.dao.day
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.HistoryCursor
import com.stash.core.data.listening.ListeningHistoryPreference
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.feature.listening.DAY_NEEDS_CONNECTION
import com.stash.feature.listening.ListeningLabels
import com.stash.feature.listening.PlayPlan
import com.stash.feature.listening.dayQueue
import com.stash.feature.listening.listQueue
import com.stash.feature.listening.planPlay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** A run of History rows heard on one [day], newest first, under one sticky header. */
data class HistoryDay(
    val day: Long,
    /** Stable LazyColumn key. A day only repeats after travel across the date line. */
    val key: String,
    val rows: List<HistoryRow>,
)

data class HistoryUiState(
    /** Loaded History, newest first, grouped by day. */
    val days: List<HistoryDay> = emptyList(),
    /** Listens and time per day, every day with a listen (headers and the calendar). */
    val dayCounts: Map<Long, DayCount> = emptyMap(),
    /** The phone's today, epoch-day. */
    val today: Long = 0,
    /** The search as typed (the list follows it 250 ms later). */
    val query: String = "",
    /** The first page for the current search or jump is loading. */
    val loading: Boolean = true,
    /** Older listens than the last loaded row exist. */
    val hasOlder: Boolean = false,
    /** Newer listens than the first loaded row exist: the list was jumped to an older day. */
    val hasNewer: Boolean = false,
    /** History is paused in Settings. */
    val paused: Boolean = false,
    /** The last older page failed to load: the list ends in a Retry instead of a spinner. */
    val olderFailed: Boolean = false,
    /**
     * Bumped when the list is replaced (a search, a jump, back to today) rather than grown or read
     * again in place: the screen keeps the user's row on screen only within one list.
     */
    val listId: Int = 0,
) {
    /** No listen at all yet (not just none matching the search). */
    val coldStart: Boolean get() = !loading && dayCounts.isEmpty() && days.isEmpty() && query.isBlank()

    /** The cold-start card, unless the paused card already says why nothing is here. */
    val showColdStart: Boolean get() = coldStart && !paused

    /** The search has no match. */
    val noMatches: Boolean get() = !loading && days.isEmpty() && query.isNotBlank()
}

/**
 * History: every listen, newest first, in keyset pages ([ListeningHistoryRepository.historyPage]).
 *
 * The list is a window of rows that grows both ways. Normally it starts at the newest listen and
 * pages down ([loadOlder]); a calendar jump to a day that isn't loaded restarts it at that day,
 * and scrolling up then pages towards today ([loadNewer]). While the window holds the newest
 * listen, a listen recorded with History open slides in on top. Search restarts the window too,
 * 250 ms after the last keystroke: a search with no match reads the whole history (~275 ms at
 * 60k listens on a Pixel 5), so it mustn't run per keystroke.
 *
 * Every restart bumps [generation]; a page that lands after one is dropped.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class HistoryViewModel @VisibleForTesting internal constructor(
    savedStateHandle: SavedStateHandle,
    private val history: ListeningHistoryRepository,
    private val historyPreference: ListeningHistoryPreference,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val zone: () -> ZoneId,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        history: ListeningHistoryRepository,
        historyPreference: ListeningHistoryPreference,
        playerRepository: PlayerRepository,
        musicRepository: MusicRepository,
        connectivityMonitor: ConnectivityMonitor,
    ) : this(
        savedStateHandle, history, historyPreference, playerRepository, musicRepository, connectivityMonitor,
        { ZoneId.systemDefault() },
    )

    private val _state = MutableStateFlow(
        HistoryUiState(today = history.today(), query = savedStateHandle.get<String>(ARG_QUERY).orEmpty()),
    )
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off messages for the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _scrollToDay = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /** The screen scrolls to this day's header (the first day on or before it that's listed). */
    val scrollToDay: SharedFlow<Long> = _scrollToDay.asSharedFlow()

    /** The user's playlists, for Save to Playlist. */
    val userPlaylists = musicRepository.getUserCreatedPlaylists()

    private val savedState = savedStateHandle

    /** The search as typed; kept across process death so a restored screen shows the same list. */
    private val queryInput = MutableStateFlow(savedStateHandle.get<String>(ARG_QUERY).orEmpty())

    /** The song playing now, so its rows are marked like in every other list. */
    val nowPlayingTrackId: StateFlow<Long?> = playerRepository.playerState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The loaded rows, newest first. Main thread only. */
    private val rows = ArrayList<HistoryRow>()
    private val rowIds = HashSet<Long>()
    private var olderCursor: HistoryCursor? = null
    private var hasOlder = false
    private var hasNewer = false
    private var appliedQuery = ""
    private var generation = 0
    private var olderJob: Job? = null
    private var newerJob: Job? = null
    private var syncJob: Job? = null
    private var playJob: Job? = null

    init {
        val startDay = savedStateHandle.get<Long>(ARG_DAY)?.takeIf { it != NO_DAY }
        viewModelScope.launch {
            var first = true
            // Empty = the search was cleared: show everything at once.
            // collectLatest: a slow read (a search with no match reads everything) gives way to the
            // next keystroke instead of finishing first.
            queryInput.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .collectLatest { q ->
                    if (first && startDay != null) jump(startDay, q) else restart(q)
                    first = false
                }
        }
        viewModelScope.launch {
            historyPreference.paused.collect { paused -> _state.update { it.copy(paused = paused) } }
        }
        viewModelScope.launch {
            // Every day there is (the index makes this an all-time read of tens of ms).
            // The counts also re-run on unrelated `tracks` writes (a sync, a download): only a real
            // change goes on. The upper bound leaves room for years of "today" moving on.
            history.observeDayCounts(DayRange(0, history.today() + DAYS_AHEAD)).distinctUntilChanged().collect { counts ->
                val map = counts.associateBy { it.day }
                _state.update { it.copy(dayCounts = map, today = history.today()) }
                onHistoryChanged(map)
            }
        }
    }

    // ── Search ───────────────────────────────────────────────────────

    fun onQueryChange(query: String) {
        queryInput.value = query
        savedState[ARG_QUERY] = query
        _state.update { it.copy(query = query) }
    }

    // ── Paging ───────────────────────────────────────────────────────

    /** The list neared its end: the next older page, if any and none is loading. */
    fun loadOlder() {
        if (!hasOlder || olderJob?.isActive == true || _state.value.loading) return
        val gen = generation
        val cursor = olderCursor
        _state.update { it.copy(olderFailed = false) }
        olderJob = viewModelScope.launch {
            val page = runCatchingLoad { history.historyPage(cursor, appliedQuery) }
            if (gen != generation) return@launch
            if (page == null) {
                _state.update { it.copy(olderFailed = true) }
                return@launch
            }
            appendOlder(page.rows)
            olderCursor = page.next
            hasOlder = page.next != null
            publish()
        }
    }

    /** The Retry at the end of the list, after an older page failed. */
    fun retryOlder() = loadOlder()

    /** The screen resumed or the clock passed midnight: "Today" and "Yesterday" move on. */
    fun refreshToday() {
        val today = history.today()
        if (today != _state.value.today) _state.update { it.copy(today = today) }
    }

    /** The list neared its top after a jump, or a listen just landed: the next newer page. */
    fun loadNewer() {
        if (newerJob?.isActive == true || _state.value.loading) return
        val top = rows.firstOrNull() ?: return
        val gen = generation
        newerJob = viewModelScope.launch {
            val page = runCatchingLoad {
                history.historyPageNewer(HistoryCursor(top.event.startedAt, top.event.id), appliedQuery)
            } ?: return@launch
            if (gen != generation) return@launch
            val fresh = page.rows.filter { rowIds.add(it.event.id) }
            rows.addAll(0, fresh)
            hasNewer = page.next != null
            publish()
        }
    }

    /** Back from a jump: the newest listens again, from the top. */
    fun backToLatest() {
        viewModelScope.launch {
            restart(appliedQuery)
            _scrollToDay.tryEmit(Long.MAX_VALUE)
        }
    }

    /** The paused card's Resume: history records again from the next song. */
    fun resumeHistory() {
        viewModelScope.launch { historyPreference.setPaused(false) }
    }

    // ── Calendar ─────────────────────────────────────────────────────

    /**
     * Shows [day]. Loaded already (or inside the loaded stretch): just scroll there. Otherwise the
     * list restarts at the end of that day and can page both ways from it.
     */
    fun jumpTo(day: Long) {
        val newest = rows.firstOrNull()?.event?.day
        val oldest = rows.lastOrNull()?.event?.day
        // Inside the loaded stretch, or past an end of it that is the end of the history.
        val inside = newest != null && oldest != null &&
            (day <= newest || !hasNewer) && (day >= oldest || !hasOlder)
        if (inside) {
            _scrollToDay.tryEmit(day)
            return
        }
        viewModelScope.launch { jump(day, appliedQuery) }
    }

    // ── Playing ──────────────────────────────────────────────────────

    /**
     * Plays the tapped listen's song and queues the list below it, the earlier listens, across days
     * (a day's last song used to queue nothing after it). Tapped near the end of what's loaded, the
     * next older listens are read first so the queue doesn't end early.
     */
    fun play(row: HistoryRow) {
        val index = rows.indexOfFirst { it.event.id == row.event.id }
        if (index < 0) {
            // Gone from the list since it was drawn: its day from the nearest listen.
            playDayFrom(row.event.day, row.event.id, row.event.startedAt)
            return
        }
        // Only the rows the queue can hold, not a list scrolled thousands deep.
        val from = (index - QUEUE_ABOVE).coerceAtLeast(0)
        val listed = rows.subList(from, minOf(rows.size, index + QUEUE_BELOW + 1)).toList()
        val tapped = index - from
        // While a new search loads, the rows and cursor are still the old list's: no read-ahead
        // (it would mix the new search's songs into the old list's queue).
        val cursor = olderCursor.takeIf { hasOlder && !_state.value.loading && from + listed.size == rows.size }
        val query = appliedQuery
        playJob?.cancel()
        playJob = viewModelScope.launch {
            var queueRows = listed
            if (cursor != null && listed.size - tapped <= QUEUE_READ_AHEAD) {
                // Best effort: if this read fails the loaded rows still play.
                val more = try {
                    history.historyPage(cursor, query, QUEUE_READ_AHEAD).rows
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "History read-ahead for the queue failed", e)
                    emptyList()
                }
                val seen = listed.mapTo(HashSet()) { it.event.id }
                queueRows = listed + more.filter { seen.add(it.event.id) }
            }
            val queue = listQueue(queueRows, tapped, before = QUEUE_ABOVE, after = QUEUE_BELOW)
            when (val plan = planPlay(queue.tracks, queue.startIndex, connectivityMonitor.isConnected(), tapped = true)) {
                is PlayPlan.Play -> playerRepository.setQueue(plan.tracks, plan.startIndex, PlaybackSource.Listening("History"))
                is PlayPlan.Blocked -> _messages.tryEmit(plan.message)
            }
        }
    }

    /** Header overflow › Play this day: the day from its first song. */
    fun playDay(day: Long) = playDayFrom(day, null, null)

    private fun playDayFrom(day: Long, startEventId: Long?, startedAt: Long?) {
        // A later tap wins: an earlier one still reading the database must not replace its queue.
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val listens = runCatchingLoad { history.listensOn(day) } ?: return@launch
            val queue = dayQueue(listens, startEventId, startedAt)
            val plan = planPlay(
                queue.tracks, queue.startIndex, connectivityMonitor.isConnected(),
                tapped = startEventId != null, noneMessage = DAY_NEEDS_CONNECTION,
            )
            when (plan) {
                is PlayPlan.Play -> playerRepository.setQueue(
                    plan.tracks, plan.startIndex,
                    PlaybackSource.Listening("History · " + ListeningLabels.absoluteDay(day, _state.value.today)),
                )
                is PlayPlan.Blocked -> _messages.tryEmit(plan.message)
            }
        }
    }

    fun playNext(track: Track) {
        viewModelScope.launch { playerRepository.addNext(track) }
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch { playerRepository.addToQueue(track) }
    }

    fun saveToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch { musicRepository.addTrackToPlaylist(trackId, playlistId) }
    }

    fun createPlaylistAndAdd(name: String, trackId: Long) {
        viewModelScope.launch {
            val id = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, id)
        }
    }

    // ── Removing ─────────────────────────────────────────────────────

    /** Long-press › Remove from history: this one listen, from this phone. */
    fun removeListen(row: HistoryRow) {
        viewModelScope.launch {
            val removed = runCatchingLoad { history.deleteListen(row.event.id) } ?: return@launch
            if (rowIds.remove(row.event.id)) rows.removeAll { it.event.id == row.event.id }
            publish()
            if (removed > 0) _messages.tryEmit("Removed from history")
        }
    }

    /** Header overflow › Remove this day (after the screen's confirmation). */
    fun removeDay(day: Long) {
        viewModelScope.launch {
            val removed = runCatchingLoad { history.deleteDay(day) } ?: return@launch
            dropDays { it == day }
            publish()
            val today = _state.value.today
            // "Removed today from history", "Removed Sat, Oct 11 from history".
            val title = ListeningLabels.dayTitle(day, today).let { if (day >= today - 1) it.lowercase() else it }
            _messages.tryEmit(if (removed > 0) "Removed $title from history" else "Nothing to remove")
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    /** Starts the list again at the newest listen for [query]. */
    private suspend fun restart(query: String) {
        val newSearch = query != appliedQuery
        val gen = beginReload(query)
        val page = runCatchingLoad { history.historyPage(null, query) }
        if (gen != generation) return
        rows.clear(); rowIds.clear()
        page?.let { appendOlder(it.rows) }
        olderCursor = page?.next
        hasOlder = page?.next != null
        hasNewer = false
        _state.update { it.copy(loading = false) }
        publish(newList = true)
        // A different search starts at its top, not where the last list was scrolled to.
        if (newSearch) _scrollToDay.tryEmit(Long.MAX_VALUE)
    }

    /** Starts the list at the end of [day] (the phone's zone) for [query], pageable both ways. */
    private suspend fun jump(day: Long, query: String) {
        val gen = beginReload(query)
        val endOfDay = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone()).toInstant().toEpochMilli() - 1
        val cursor = HistoryCursor(endOfDay, Long.MAX_VALUE)
        val page = runCatchingLoad { history.historyPage(cursor, query) }
        if (gen != generation) return
        rows.clear(); rowIds.clear()
        page?.let { appendOlder(it.rows) }
        olderCursor = page?.next
        hasOlder = page?.next != null
        // Whether anything newer exists is found out by paging up; assume so until then.
        hasNewer = rows.isNotEmpty()
        var target = day
        if (page != null && rows.isEmpty() && query.isNotBlank()) {
            // A search whose matches are all newer than the day: land on the nearest one after it
            // rather than say nothing matches.
            val newer = runCatchingLoad { history.historyPageNewer(cursor, query) }
            if (gen != generation) return
            if (newer != null && newer.rows.isNotEmpty()) {
                appendOlder(newer.rows)
                hasNewer = newer.next != null
                hasOlder = false
                target = rows.last().event.day
                val on = ListeningLabels.absoluteDay(day, _state.value.today)
                _messages.tryEmit("No matches on or before $on. Showing the nearest after it.")
            }
        }
        _state.update { it.copy(loading = false) }
        publish(newList = true)
        _scrollToDay.tryEmit(target)
    }

    private fun beginReload(query: String): Int {
        generation++
        // The sync job too: left running, it would read the old list again over the new one.
        olderJob?.cancel(); newerJob?.cancel(); syncJob?.cancel()
        appliedQuery = query
        _state.update { it.copy(loading = true) }
        return generation
    }

    /**
     * The history changed under the list. Days no longer in it leave the list; a list showing the
     * newest listens gets new ones on top; and when a loaded day's rows no longer add up to its
     * count (plays from the web mirror or a library file land in the past, not on top), the loaded
     * stretch is read again in place. Keys don't change, so the list stays where the user is.
     */
    private fun onHistoryChanged(counts: Map<Long, DayCount>) {
        if (_state.value.loading) return
        if (rows.isNotEmpty() && dropDays { it !in counts }) publish()
        if (rows.isEmpty()) {
            // The first listen arrived while History was open (a search with no match stays as it is).
            if (counts.isNotEmpty() && !hasOlder && appliedQuery.isBlank()) viewModelScope.launch { restart(appliedQuery) }
            return
        }
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (!hasNewer) {
                loadNewer()
                newerJob?.join()
            }
            // A search's rows can't be checked against whole-day counts.
            if (appliedQuery.isBlank() && loadedDaysDisagree(_state.value.dayCounts)) reloadWindow()
        }
    }

    /**
     * True when a day whose listens are all loaded holds a different number of rows than its count.
     * The oldest loaded day may continue on the next page, and the newest on the page above.
     */
    private fun loadedDaysDisagree(counts: Map<Long, DayCount>): Boolean {
        if (rows.isEmpty()) return false
        val newest = rows.first().event.day
        val oldest = rows.last().event.day
        return rows.groupingBy { it.event.day }.eachCount().any { (day, n) ->
            (day != oldest || !hasOlder) && (day != newest || !hasNewer) && counts[day]?.plays != n
        }
    }

    /** Reads the loaded stretch again, from its newest row down, as many rows as it held. */
    private suspend fun reloadWindow() {
        // A restart or jump in flight replaces the list anyway, and owns `loading`.
        if (_state.value.loading) return
        val top = rows.firstOrNull() ?: return
        generation++
        olderJob?.cancel(); newerJob?.cancel()
        val gen = generation
        // As many rows as the list held (at least a page), read in chunks, so a list scrolled
        // thousands of rows deep keeps its depth.
        val target = maxOf(rows.size, ListeningHistoryRepository.PAGE_SIZE)
        val fresh = ArrayList<HistoryRow>(target)
        // Just above the top row, so the read includes it.
        var cursor: HistoryCursor? = HistoryCursor(top.event.startedAt, top.event.id + 1)
        while (cursor != null && fresh.size < target) {
            val from = cursor
            val limit = minOf(target - fresh.size, MAX_WINDOW_RELOAD)
            val page = runCatchingLoad { history.historyPage(from, appliedQuery, limit) } ?: return
            if (gen != generation) return
            fresh += page.rows
            cursor = page.next
        }
        rows.clear(); rowIds.clear()
        appendOlder(fresh)
        olderCursor = cursor
        hasOlder = cursor != null
        publish()
    }

    /** Removes every loaded row whose day matches; true when any went. */
    private fun dropDays(predicate: (Long) -> Boolean): Boolean {
        val before = rows.size
        rows.removeAll { row -> predicate(row.event.day).also { gone -> if (gone) rowIds.remove(row.event.id) } }
        return rows.size != before
    }

    private fun appendOlder(page: List<HistoryRow>) {
        for (row in page) if (rowIds.add(row.event.id)) rows += row
    }

    /** [newList]: the rows replace the list (a search, a jump) rather than grow it. */
    private fun publish(newList: Boolean = false) {
        _state.update {
            it.copy(
                days = groupByDay(rows), hasOlder = hasOlder, hasNewer = hasNewer,
                listId = if (newList) it.listId + 1 else it.listId,
            )
        }
    }

    /** A read that failed (a closed database, a migration in progress) reads as nothing loaded. */
    private suspend fun <T> runCatchingLoad(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(TAG, "History read failed", e)
        _messages.tryEmit("Couldn't read your history. Try again.")
        null
    }

    companion object {
        private const val TAG = "HistoryViewModel"

        /** Nav argument: the day to open on (epoch-day), [NO_DAY] for the newest. */
        const val ARG_DAY = "day"
        const val NO_DAY = -1L

        /** A tap this close to the end of the loaded rows reads this many older ones for its queue. */
        const val QUEUE_READ_AHEAD = 100

        /** A tapped History row queues at most this many rows above it (for Previous) and below it. */
        const val QUEUE_ABOVE = 50
        const val QUEUE_BELOW = 500

        /** Search waits this long after the last keystroke (a no-match search reads everything). */
        const val SEARCH_DEBOUNCE_MS = 250L

        /** The loaded stretch is read again in chunks of at most this many rows. */
        const val MAX_WINDOW_RELOAD = 2_000

        /** Saved search, so a restored screen shows the same list. */
        const val ARG_QUERY = "history_query"

        /** How far past today the day counts read, so they stay right as days go by. */
        private const val DAYS_AHEAD = 3_650L

        /** Consecutive rows of the same day as one group each. */
        internal fun groupByDay(rows: List<HistoryRow>): List<HistoryDay> {
            val out = ArrayList<HistoryDay>()
            val seen = HashMap<Long, Int>()
            var start = 0
            while (start < rows.size) {
                val day = rows[start].event.day
                var end = start + 1
                while (end < rows.size && rows[end].event.day == day) end++
                val n = (seen[day] ?: 0) + 1
                seen[day] = n
                out += HistoryDay(day, if (n == 1) "d$day" else "d$day#$n", rows.subList(start, end).toList())
                start = end
            }
            return out
        }
    }
}
