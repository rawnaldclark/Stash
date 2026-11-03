package com.stash.core.data.discography
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.DatedRelease

/**
 * Result of merging Qobuz albums into a YT discography. [releaseDates] are the artist's releases
 * that can still be new ([newReleaseYears]), dated to the day, for the artist page's Latest
 * releases row; empty when the supplement found nothing (or wasn't sure of the artist).
 */
data class MergedDiscography(
    val albums: List<AlbumSummary>,
    val singles: List<AlbumSummary>,
    val releaseDates: List<DatedRelease> = emptyList(),
)

/**
 * Supplements a YT-sourced discography with Qobuz albums. The impl (in
 * data:download, where the Qobuz client + matcher live) does the artist match,
 * fetch, and merge; core:data only calls this and caches the result.
 *
 * Best-effort: any failure/timeout/no-confident-match returns the YT lists
 * UNCHANGED. Never throws to the caller.
 */
interface DiscographySupplement {
    suspend fun mergeInto(
        artistName: String,
        ytAlbums: List<AlbumSummary>,
        ytSingles: List<AlbumSummary>,
    ): MergedDiscography
}
