package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningHistoryPreference
import com.stash.core.data.listening.ListeningHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Listening history sheet needs to say before clearing: listens per day, all of them. */
data class ListeningHistoryCounts(
    /** Listens per epoch-day, days without a listen absent. */
    val playsByDay: Map<Long, Int> = emptyMap(),
    val today: Long = 0,
) {
    val totalPlays: Int get() = playsByDay.values.sum()

    /** The first day with a listen, or null with none. */
    val firstDay: Long? get() = playsByDay.keys.minOrNull()

    /** Listens on [fromDay]..[toDay]. */
    fun playsIn(fromDay: Long, toDay: Long): Int = playsByDay.entries.sumOf { (day, n) -> if (day in fromDay..toDay) n else 0 }
}

/**
 * Settings › Library & Storage › Listening history: pause the history, and clear all of it or a
 * date range. This phone only (owner decision 7): nothing already sent to Last.fm, ListenBrainz or
 * YouTube Music is touched, and a clear recomputes the songs' play counts
 * ([ListeningHistoryRepository.deleteRange]).
 */
@HiltViewModel
class ListeningHistorySettingsViewModel @Inject constructor(
    private val history: ListeningHistoryRepository,
    private val preference: ListeningHistoryPreference,
) : ViewModel() {

    /** For the row's subtitle and the sheet's switch. */
    val paused: StateFlow<Boolean> = preference.paused
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Collected by the sheet only: an all-time read, not worth running behind the settings list. */
    val counts: StateFlow<ListeningHistoryCounts> = history.observeDayCounts(DayRange(0, history.today() + DAYS_AHEAD))
        // The read also re-runs on unrelated `tracks` writes: only a real change goes on.
        .distinctUntilChanged()
        .map { days -> ListeningHistoryCounts(days.associate { it.day to it.plays }, history.today()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningHistoryCounts(today = history.today()))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)

    /** One-off results for a toast. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Today, read now: the range picker's last selectable day, right after midnight too. */
    fun today(): Long = history.today()

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { preference.setPaused(paused) }
    }

    /** Clears every listen on [fromDay]..[toDay] (epoch-days, inclusive). */
    fun clearRange(fromDay: Long, toDay: Long) = clear { history.deleteRange(DayRange(minOf(fromDay, toDay), maxOf(fromDay, toDay))) }

    /** Clears the whole history on this phone. */
    fun clearAll() = clear { history.deleteAll() }

    private fun clear(delete: suspend () -> Int) {
        viewModelScope.launch {
            val removed = try {
                delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ListeningHistorySettings", "Clear failed", e)
                _messages.tryEmit("Couldn't clear your history. Try again.")
                return@launch
            }
            _messages.tryEmit(
                when (removed) {
                    0 -> "Nothing to clear"
                    1 -> "Cleared 1 play"
                    else -> "Cleared $removed plays"
                },
            )
        }
    }

    private companion object {
        /** How far past today the counts read, so they stay right as days go by. */
        const val DAYS_AHEAD = 3_650L
    }
}
