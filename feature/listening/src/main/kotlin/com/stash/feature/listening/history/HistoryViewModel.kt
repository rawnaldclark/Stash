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
import kotlinx.coroutines.flow.debounce
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
) {
    /** No listen at all yet (not just none matching the search). */
    val coldStart: Boolean get() = !loading && dayCounts.isEmpty() && days.isEmpty() && query.isBlank()

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

    private val _state = MutableStateFlow(HistoryUiState(today = history.today()))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off messages for the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _scrollToDay = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /** The screen scrolls to this day's header (the first day on or before it that's listed). */
    val scrollToDay: SharedFlow<Long> = _scrollToDay.asSharedFlow()

    /** The user's playlists, for Save to Playlist. */
    val userPlaylists = musicRepository.getUserCreatedPlaylists()

    private val queryInput = MutableStateFlow("")

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

    init {
        val startDay = savedStateHandle.get<Long>(ARG_DAY)?.takeIf { it != NO_DAY }
        viewModelScope.launch {
            var first = true
            // Empty = the search was cleared: show everything at once.
            queryInput.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .collect { q ->
                    if (first && startDay != null) jump(startDay, q) else restart(q)
                    first = false
                }
        }
        viewModelScope.launch {
            historyPreference.paused.collect { paused -> _state.update { it.copy(paused = paused) } }
        }
        viewModelScope.launch {
            // Every day there is (the index makes this an all-time read of tens of ms).
            history.observeDayCounts(DayRange(0, history.today() + 1)).collect { counts ->
                val map = counts.associateBy { it.day }
                _state.update { it.copy(dayCounts = map, today = history.today()) }
                onHistoryChanged(map)
            }
        }
    }

    // ── Search ───────────────────────────────────────────────────────

    fun onQueryChange(query: String) {
        queryInput.value = query
        _state.update { it.copy(query = query) }
    }

    // ── Paging ───────────────────────────────────────────────────────

    /** The list neared its end: the next older page, if any and none is loading. */
    fun loadOlder() {
        if (!hasOlder || olderJob?.isActive == true || _state.value.loading) return
        val gen = generation
        val cursor = olderCursor
        olderJob = viewModelScope.launch {
            val page = runCatchingLoad { history.historyPage(cursor, appliedQuery) } ?: return@launch
            if (gen != generation) return@launch
            appendOlder(page.rows)
            olderCursor = page.next
            hasOlder = page.next != null
            publish()
        }
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

    /** Plays the tapped listen's song and queues the rest of that day, in the order it was heard. */
    fun play(row: HistoryRow) = playDayFrom(row.event.day, row.event.id)

    /** Header overflow › Play this day: the day from its first song. */
    fun playDay(day: Long) = playDayFrom(day, null)

    private fun playDayFrom(day: Long, startEventId: Long?) {
        viewModelScope.launch {
            val listens = runCatchingLoad { history.listensOn(day) } ?: return@launch
            val queue = dayQueue(listens, startEventId)
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
        val gen = beginReload(query)
        val page = runCatchingLoad { history.historyPage(null, query) }
        if (gen != generation) return
        rows.clear(); rowIds.clear()
        page?.let { appendOlder(it.rows) }
        olderCursor = page?.next
        hasOlder = page?.next != null
        hasNewer = false
        _state.update { it.copy(loading = false) }
        publish()
    }

    /** Starts the list at the end of [day] (the phone's zone) for [query], pageable both ways. */
    private suspend fun jump(day: Long, query: String) {
        val gen = beginReload(query)
        val endOfDay = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone()).toInstant().toEpochMilli() - 1
        val page = runCatchingLoad { history.historyPage(HistoryCursor(endOfDay, Long.MAX_VALUE), query) }
        if (gen != generation) return
        rows.clear(); rowIds.clear()
        page?.let { appendOlder(it.rows) }
        olderCursor = page?.next
        hasOlder = page?.next != null
        // Whether anything newer exists is found out by paging up; assume so until then.
        hasNewer = rows.isNotEmpty()
        _state.update { it.copy(loading = false) }
        publish()
        _scrollToDay.tryEmit(day)
    }

    private fun beginReload(query: String): Int {
        generation++
        olderJob?.cancel(); newerJob?.cancel()
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
        val top = rows.firstOrNull() ?: return
        generation++
        olderJob?.cancel(); newerJob?.cancel()
        val gen = generation
        // Just above the top row, so the read includes it.
        val cursor = HistoryCursor(top.event.startedAt, top.event.id + 1)
        val limit = rows.size.coerceIn(ListeningHistoryRepository.PAGE_SIZE, MAX_WINDOW_RELOAD)
        val page = runCatchingLoad { history.historyPage(cursor, appliedQuery, limit) } ?: return
        if (gen != generation) return
        rows.clear(); rowIds.clear()
        appendOlder(page.rows)
        olderCursor = page.next
        hasOlder = page.next != null
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

    private fun publish() {
        _state.update { it.copy(days = groupByDay(rows), hasOlder = hasOlder, hasNewer = hasNewer) }
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

        /** Search waits this long after the last keystroke (a no-match search reads everything). */
        const val SEARCH_DEBOUNCE_MS = 250L

        /** At most this many rows are read again when the loaded stretch is refreshed in place. */
        const val MAX_WINDOW_RELOAD = 2_000

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
