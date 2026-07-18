package com.stash.core.data.discovery

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary

/**
 * Home discovery rows sourced from Qobuz featured endpoints.
 *
 * Impl lives in `data:download` (it needs the qbdlx client + token pool, which
 * `core:data` can't see) and is bound via Hilt — mirrors [QobuzAlbumFetcher].
 *
 * Fail-soft by contract: discovery is non-critical, so any failure (no live
 * token, network error, empty catalog) returns an empty list rather than
 * throwing. The caller hides an empty row; it never blocks Home.
 *
 * [genreId] null = all genres (the "All" chip).
 */
interface HomeDiscoveryRepository {
    suspend fun newReleases(genreId: Int?): List<AlbumSummary>
    suspend fun topAlbums(genreId: Int?): List<AlbumSummary>
    suspend fun communityPlaylists(genreId: Int?): List<PlaylistSummary>

    /**
     * One page of the editorial playlist catalog for the "See all" browse
     * screen — [offset] into the ~6.3k playlists, filtered by [genreId]
     * (null = all). Fail-soft (empty on error). A short page signals the end.
     */
    suspend fun browsePlaylists(genreId: Int?, offset: Int, limit: Int = 30): List<PlaylistSummary>

    /**
     * One page of catalog-wide playlist search for the browse screen's search
     * field. Global by nature — Qobuz's catalog/search has no genre filter.
     * Fail-soft (empty on error). A short page signals the end.
     */
    suspend fun searchPlaylists(query: String, offset: Int, limit: Int = 30): List<PlaylistSummary>
}
