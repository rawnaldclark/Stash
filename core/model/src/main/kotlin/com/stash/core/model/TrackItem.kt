package com.stash.core.model

/**
 * Lightweight representation of a track as seen from the search tab.
 *
 * Originally nested inside [com.stash.core.media.actions.TrackActionsDelegate],
 * moved to `:core:model` so that `:data:download` can reference it from
 * [com.stash.data.download.search.SearchDownloadCoordinator] without
 * creating a circular module dependency
 * (`:core:media` → `:data:download` → `:core:media`).
 *
 * Only carries the fields the coordinator and prefetcher need:
 * a stable [videoId] for dedup, [title]/[artist] for identity matching
 * and metadata embedding, [durationSeconds] for lossless-source duration
 * filtering, and [thumbnailUrl] for the cover-art fallback.
 *
 * [album] is nullable because the field is only meaningful for items
 * sourced from album-context flows (Album Discovery screen, an artist
 * profile's album shelf). Loose search-result rows leave it null. Carrying
 * it through is what makes downloaded album tracks render as an album in
 * the Library (TrackDao.getAllAlbums groups by tracks.album and excludes
 * empty values).
 */
data class TrackItem(
    val videoId: String,
    val title: String,
    val artist: String,
    /** Duration as a Double so sub-second precision is preserved. */
    val durationSeconds: Double,
    val thumbnailUrl: String?,
    val album: String? = null,
    /**
     * v0.9.26 — primary credit on the album as a whole (distinct from
     * per-track [artist] credits). Populated when the user downloads
     * via album-context flows (AlbumDiscoveryScreen), null for loose
     * search results. Persisted to `tracks.album_artist` so the Library
     * Albums query can disambiguate same-named releases by different
     * artists.
     */
    val albumArtist: String? = null,
    /**
     * The result's video type as `tracks.music_video_type` stores it ("ATV", "OMV", ...), null
     * when the search didn't say. A music video's length is the video's, so the lossless
     * lookups treat it as a hint ([com.stash.core.model.Track.musicVideoType]).
     */
    val musicVideoType: String? = null,
    /** True when YouTube Music badges the result explicit; null otherwise (it marks only explicit). */
    val explicit: Boolean? = null,
)
