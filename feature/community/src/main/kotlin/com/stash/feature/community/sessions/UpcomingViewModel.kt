package com.stash.feature.community.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.model.community.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Sessions screen's Upcoming: stations on a schedule that go live on their own within a day, soonest first.
 * Read when the screen shows; a failed read keeps what was there (the list is a nicety, never an error card).
 */
@HiltViewModel
class UpcomingViewModel @Inject constructor(private val stations: StationsRepository) : ViewModel() {
    private val _upcoming = MutableStateFlow<List<Station>>(emptyList())
    val upcoming: StateFlow<List<Station>> = _upcoming.asStateFlow()
    private var loading = false

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                val r = stations.upcoming()
                // Only ones with a time to show; your own station isn't news to you.
                if (r is CommunityResult.Ok) _upcoming.value = r.value.filter { it.next != null && !it.mine }
            } finally {
                loading = false
            }
        }
    }
}

/** When a station is next on, as a short line: the day's short name and the time, on this phone's clock. */
internal fun nextOnText(startMs: Long, zone: java.time.ZoneId, locale: java.util.Locale): String {
    val at = java.time.Instant.ofEpochMilli(startMs).atZone(zone)
    val day = at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
    return "$day " + java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(at)
}
