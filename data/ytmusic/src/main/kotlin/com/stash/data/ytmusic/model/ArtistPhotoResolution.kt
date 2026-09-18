package com.stash.data.ytmusic.model

/**
 * Outcome of an artist-photo resolution ([com.stash.data.ytmusic.YTMusicApiClient.resolveArtistPhoto]).
 *
 * Tri-state on purpose, so the backfill worker can tell a genuine "no photo"
 * answer apart from a failed request:
 *
 * - [Resolved] — the API answered and found the artist; [avatarUrl] may still
 *   be null when the artist channel has no avatar.
 * - [NoAvatar] — the API answered but had no matching artist — a permanent
 *   "no photo", safe to stamp as a sentinel.
 * - [Failed] — the API did not answer (network failure, 4xx/5xx, rate limit).
 *   NOT a "no photo"; the caller should retry later, not give up.
 */
sealed interface ArtistPhotoResolution {
    /** The API answered and found the artist. */
    data class Resolved(val avatarUrl: String?) : ArtistPhotoResolution

    /** The API answered but had no matching artist — a genuine no-photo. */
    data object NoAvatar : ArtistPhotoResolution

    /** The API did not answer (network / HTTP failure / rate limit). */
    data object Failed : ArtistPhotoResolution
}