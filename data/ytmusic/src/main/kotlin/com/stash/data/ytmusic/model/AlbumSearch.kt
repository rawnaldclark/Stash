package com.stash.data.ytmusic.model

/**
 * Outcome of an album search ([com.stash.data.ytmusic.YTMusicApiClient.searchAlbums]).
 *
 * Tri-state like [ArtistPhotoResolution], so the caller can tell "YouTube answered
 * and named no such album" apart from "YouTube didn't answer":
 *
 * - [Answered] — the search came back. Its candidates are YouTube's top-result card
 *   when that card is an album ([Answered.topAlbum]), then the Albums shelf. Both
 *   can be empty: an answer that names no album.
 * - [Failed] — no answer (network failure, timeout, 429, 5xx). Not a "no album";
 *   worth a retry.
 */
sealed interface AlbumSearch {
    /** The search came back; [albums] lists every candidate, YouTube's best guess first. */
    data class Answered(val topAlbum: AlbumSummary?, val shelf: List<AlbumSummary>) : AlbumSearch {
        val albums: List<AlbumSummary> get() = listOfNotNull(topAlbum) + shelf
    }

    /** The search didn't come back (network / HTTP failure / rate limit). */
    data object Failed : AlbumSearch
}
