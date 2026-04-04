package com.stash.feature.search

/**
 * UI state for the search screen.
 *
 * @property query          Current text in the search field.
 * @property results        List of search results to display.
 * @property isSearching    Whether a search request is currently in flight.
 * @property downloadingIds Set of video IDs that are currently being downloaded.
 * @property downloadedIds  Set of video IDs that have already been downloaded.
 * @property error          Error message from the last failed search, if any.
 */
data class SearchUiState(
    val query: String = "",
    val results: List<SearchResultItem> = emptyList(),
    val isSearching: Boolean = false,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val error: String? = null,
)

/**
 * Represents a single search result from YouTube Music.
 *
 * @property videoId         YouTube video ID (e.g. "dQw4w9WgXcQ").
 * @property title           Track title as it appears on YouTube.
 * @property artist          Uploader/channel name serving as the artist.
 * @property durationSeconds Duration of the track in seconds.
 */
data class SearchResultItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Double,
    val thumbnailUrl: String? = null,
)
