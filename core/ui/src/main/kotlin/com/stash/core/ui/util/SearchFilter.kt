package com.stash.core.ui.util

import com.stash.core.model.Track
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Filters a track list flow using a debounced search query.
 *
 * Matches against [Track.title] and [Track.artist] using case-insensitive
 * contains. An empty query passes the full list through unchanged and
 * is not subject to the 300ms debounce — this avoids a visible loading
 * flash when the screen first opens.
 *
 * The raw [queryFlow] value should be used directly for UI display
 * (text field binding). This function handles debouncing internally
 * for filtering only.
 *
 * Uses 300ms debounce (not 500ms like SearchScreen) because this is
 * client-side string matching, not a network call.
 */
@OptIn(FlowPreview::class)
fun Flow<List<Track>>.withSearchFilter(
    queryFlow: StateFlow<String>,
): Flow<List<Track>> {
    val tracksFlow = this
    return queryFlow
        .debounce { query -> if (query.isEmpty()) 0L else 300L }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) {
                tracksFlow
            } else {
                tracksFlow.map { tracks ->
                    val lowerQuery = query.lowercase()
                    tracks.filter { track ->
                        track.title.lowercase().contains(lowerQuery) ||
                            track.artist.lowercase().contains(lowerQuery)
                    }
                }
            }
        }
}
