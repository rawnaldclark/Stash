package com.stash.feature.search

import com.stash.data.ytmusic.model.SearchResultSection

/**
 * Discriminator for the current phase of the Search tab.
 *
 * Moved from scattered nullable fields on [SearchUiState] (Task 9) so the
 * UI can render with a single `when (state.status)` and avoid the previous
 * "isSearching && error != null" ambiguity.
 */
sealed interface SearchStatus {
    /** No query typed yet — render the empty prompt. */
    data object Idle : SearchStatus

    /** A `searchAll` call is in flight; show loading skeletons. */
    data object Loading : SearchStatus

    /** Results arrived — the list of ordered sections to render. */
    data class Results(val sections: List<SearchResultSection>) : SearchStatus

    /** The query completed with zero matching sections. */
    data object Empty : SearchStatus

    /** The search call threw; [message] is shown in the error view. */
    data class Error(val message: String) : SearchStatus
}

/**
 * UI state for the Search screen.
 *
 * Task 9 collapses the previous `results/isSearching/error/previewError`
 * fields into [status]; the download + preview state remain on the root
 * state because they're cross-cutting (multiple downloads can be in flight
 * regardless of the current search phase).
 *
 * @property query            Current text in the search field.
 * @property status           Current search phase — see [SearchStatus].
 * @property downloadingIds   Set of video IDs currently being downloaded.
 * @property downloadedIds    Set of video IDs already downloaded.
 * @property previewLoading   Video ID of the preview currently being
 *   extracted (spinner icon on the row), or null when no preview is
 *   loading.
 */
data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.Idle,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val previewLoading: String? = null,
)

/**
 * Lightweight bridge between sectioned-search results ([TrackSummary]) and
 * the download pipeline which still speaks [SearchResultItem].
 *
 * Kept in this file because it's only consumed from within the search
 * module (VM + PreviewDownloadRow adapters).
 *
 * @property videoId         YouTube video ID.
 * @property title           Track title as it appears on YouTube.
 * @property artist          Uploader/channel name serving as the artist.
 * @property durationSeconds Duration of the track in seconds.
 * @property thumbnailUrl    Optional album-art thumbnail URL.
 */
data class SearchResultItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Double,
    val thumbnailUrl: String? = null,
)
