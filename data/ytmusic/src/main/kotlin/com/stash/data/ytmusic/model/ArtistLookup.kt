package com.stash.data.ytmusic.model

/**
 * Outcome of looking an artist name up on YouTube Music ([com.stash.data.ytmusic.YTMusicApiClient.lookUpArtist]).
 *
 * Three-way on purpose, like [ArtistPhotoResolution], so a caller can remember a real "not there" and retry a
 * request that never answered:
 *
 * - [Found]: the search answered and an artist on its shelf names the one wanted. [exact] when the names are
 *   equal (folded); false when one only contains the other ("Bon" and "Bon Iver"), which the caller should check
 *   some other way before trusting it.
 * - [NotFound]: the search answered and nobody on the shelf is that artist. Safe to remember.
 * - [NoAnswer]: the search didn't answer (network, 4xx/5xx, rate limit). Not a "not found": try again later.
 */
sealed interface ArtistLookup {
    data class Found(val artist: ArtistSummary, val exact: Boolean) : ArtistLookup
    data object NotFound : ArtistLookup
    data object NoAnswer : ArtistLookup
}
